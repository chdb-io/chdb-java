package org.chdb.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.chdb.internal.ChdbNative;
import org.chdb.internal.ChdbNativeException;
import org.chdb.internal.NativeLibraryLoader;
import org.chdb.internal.UnsupportedPlatformException;

/**
 * A chDB connection: one engine session over the process's storage path.
 *
 * <h2>Transactions</h2>
 * There are none. chDB is an embedded analytical engine with no transaction manager, so
 * {@link #setAutoCommit(boolean)} with {@code false}, {@link #commit()}, {@link #rollback()}
 * and every savepoint method throw {@link java.sql.SQLFeatureNotSupportedException} rather
 * than pretending (work plan section 3.4). Reporting fake transaction support is worse than
 * refusing: a framework would build a unit of work that silently is not one.
 *
 * <h2>Concurrency</h2>
 * The engine runs one statement per connection at a time, and that includes the fetches a
 * result set makes after {@code executeQuery} has returned. {@link StatementSlot} enforces it:
 * a statement holds the connection from the moment it starts until its result set is closed,
 * and a second caller waits. {@code Statement.cancel()} is the deliberate exception -- it has
 * to run while a query is in flight, so it never takes the slot.
 *
 * <p>Waiting rather than rejecting means a connection pool is safe without knowing any of
 * this: each pooled Connection is a separate engine session, and a pool that shares one
 * across threads gets correct results instead of a corrupted engine.
 */
public final class ChdbConnection implements Connection {

    private final ChdbUrl url;
    private final long handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Held for the whole of {@link #close()}, so a second caller waits for the first to finish
     * rather than returning as soon as the flag is set.
     *
     * <p>The caller that needs this is the shutdown hook: it closes connections that may
     * already be closing on an application thread, and the JVM does not wait for that thread
     * once the hooks return. Without the wait the hook can finish while a stream is still open
     * in the engine, which is the abort {@link ShutdownCleanup} exists to prevent.
     */
    private final Object closeLock = new Object();

    /**
     * Serializes statement execution on this connection, for as long as a statement is live.
     * Not taken by {@code cancel}: the whole point of cancel is to interrupt the holder.
     */
    private final StatementSlot statementSlot = new StatementSlot();

    /**
     * Open statements, so {@link #close()} can close them first. A concurrent set because a
     * statement may be closed from a different thread than the one that created it.
     */
    private final Set<ChdbStatement> openStatements =
            Collections.newSetFromMap(new ConcurrentHashMap<ChdbStatement, Boolean>());

    /**
     * Arbitrates between a thread starting a statement here and the shutdown hook closing this
     * connection, which must never overlap: closing a connection whose statement is executing
     * aborted the process in 21 runs of 60 on engine 26.7.2-rc.2, macOS arm64, against 0 of 60
     * with the hook off, and blocked until the query finished in the runs that survived -- 34 s
     * for four threads on a five-billion-row aggregate, 79 s for a longer one, against 1.1 s
     * once those connections are skipped.
     *
     * <p>A gate rather than a counter the hook reads, because a read followed by a close is
     * check-then-act and a shutdown hook runs alongside application threads. {@link
     * ExecutionGate} has the reasoning and the reason it is not a lock; {@link ShutdownCleanup}
     * has the whole measurement table.
     *
     * <p>Not the same question as {@link #statementSlot}, which is held from execution until
     * the result set closes -- so a leaked stream on a parked thread holds it too, and that is
     * precisely the connection the hook must close. What distinguishes them is whether a
     * thread is inside the engine right now.
     */
    private final ExecutionGate executionGate = new ExecutionGate();

    private final Map<String, String> clientInfo = new LinkedHashMap<>();
    private volatile boolean readOnly;
    private volatile String catalog;
    private volatile String schema;
    private volatile int networkTimeoutMillis;

    ChdbConnection(ChdbUrl url) throws SQLException {
        this.url = url;

        try {
            // Loads libchdb and the shim. The opt-out from chDB's signal handlers happens
            // inside this call, once per process -- not here, per connection, as it used to.
            // The opt-out resets the JVM's own SIGSEGV/SIGBUS/SIGILL/SIGFPE handlers as a
            // side effect and the shim restores them (work plan section 5.6), but the restore
            // cannot be atomic: dispositions are process-wide, so every call leaves a window
            // in which another thread taking one of those signals dies with no handler.
            // Doing it per connection doubled the number of those windows for nothing, since
            // the flag it sets is sticky. See issue #14 and NativeLibraryLoader.load().
            NativeLibraryLoader.ensureLoaded();
        } catch (UnsupportedPlatformException e) {
            throw ChdbExceptions.wrap("Cannot connect to " + url.url(), e);
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Cannot load the chDB native runtime", e);
        }

        StoragePathRegistry.acquire(url);

        // Announced before the handle exists and disowned only after it is registered, so the
        // shutdown hook can tell "nothing is open" from "something is opening". Between
        // chdb_connect() returning and register() completing, the engine holds a live handle
        // that the hook's registry knows nothing about; a hook that ran in that window used to
        // find an empty registry and return, leaving this connection's stream to be caught by
        // the exit-time abort. See ShutdownCleanup.CONNECTS_IN_FLIGHT.
        //
        // The finally is the whole point. A count left raised by a connect that threw would
        // make every subsequent JVM exit in this process wait out the drain's entire budget,
        // so this must unwind on the failure paths too -- which is why it wraps the catch
        // clauses rather than sitting inside the try.
        ShutdownCleanup.connectStarted();
        try {
            boolean opened = false;
            try {
                this.handle = ChdbNative.connect(Utf8.encodeAll(url.toConnectArguments()));
                opened = true;
            } catch (ChdbNativeException e) {
                throw ChdbExceptions.wrap("Cannot connect to " + url.url(), e);
            } finally {
                if (!opened) {
                    // A failed connect must not leave the storage path pinned, or the next
                    // attempt at a different path fails for a connection that does not exist.
                    StoragePathRegistry.release(url);
                }
            }

            // A JVM that exits with a streaming result set still open aborts inside the engine.
            // Closing connections at shutdown closes their result sets, which is the state the
            // engine tolerates. See ShutdownCleanup.
            ShutdownCleanup.register(this);
        } finally {
            ShutdownCleanup.connectFinished();
        }
    }

    // ------------------------------------------------------------------ internals

    long handle() {
        return handle;
    }

    ChdbUrl chdbUrl() {
        return url;
    }

    StatementSlot statementSlot() {
        return statementSlot;
    }

    /**
     * Announces that this thread is about to start a statement in the engine.
     *
     * <p>{@code false} means the shutdown hook has taken this connection and is closing it, so
     * the caller must not reach the engine. Paired with {@link #executionFinished()} from a
     * {@code finally} whenever it returns {@code true}: a count left raised would make the
     * hook skip this connection for the rest of the process, which is a silent leak of the
     * thing the hook is for.
     */
    boolean executionStarted() {
        return executionGate.enter();
    }

    void executionFinished() {
        executionGate.exit();
    }

    /**
     * Takes this connection for the shutdown hook, if no statement is starting on it.
     *
     * <p>Called by {@link ShutdownCleanup}'s drain while it holds its registry monitor, but the
     * exclusion comes from the gate rather than from that monitor — which is what lets the
     * drain call {@link #close()} after releasing it. See {@link ExecutionGate}.
     *
     * @return whether the hook may now close this connection
     */
    boolean claimForShutdownClose() {
        return executionGate.closeToNewEntrants();
    }

    /** Whether the shutdown hook has claimed this connection. For the refusal message. */
    boolean isClaimedForShutdownClose() {
        return executionGate.isClosedToNewEntrants();
    }

    /**
     * How many threads are inside a statement-start call here. For diagnostics and tests.
     *
     * <p>Non-terminal, unlike {@link #claimForShutdownClose()}, which is what makes it usable
     * for checking that a statement that failed put the gate back. A count stuck above zero
     * would make the shutdown hook skip this connection for the rest of the process and refuse
     * nothing, so the leak is silent both ways.
     */
    int executionsInFlight() {
        return executionGate.inFlight();
    }

    void register(ChdbStatement statement) {
        openStatements.add(statement);
    }

    void unregister(ChdbStatement statement) {
        openStatements.remove(statement);
    }

    void checkOpen() throws SQLException {
        if (closed.get()) {
            throw ChdbExceptions.closed("Connection");
        }
    }

    // ------------------------------------------------------------------ statements

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        ChdbStatement statement = new ChdbStatement(this);
        register(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        ChdbPreparedStatement statement = new ChdbPreparedStatement(this, sql);
        register(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw ChdbExceptions.notSupported("Generated keys");
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    /**
     * A result set is forward-only and read-only, always. Asking for anything else is
     * refused rather than silently downgraded, so a caller that needs a scrollable cursor
     * finds out here instead of at the first {@code previous()}.
     */
    private void checkResultSetOptions(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkOpen();
        if (resultSetType != java.sql.ResultSet.TYPE_FORWARD_ONLY) {
            throw ChdbExceptions.notSupported(
                    "Scrollable result sets (requested type " + resultSetType
                            + "; only TYPE_FORWARD_ONLY is available)");
        }
        if (resultSetConcurrency != java.sql.ResultSet.CONCUR_READ_ONLY) {
            throw ChdbExceptions.notSupported(
                    "Updatable result sets (requested concurrency " + resultSetConcurrency
                            + "; only CONCUR_READ_ONLY is available)");
        }
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw ChdbExceptions.notSupported("CallableStatement");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        throw ChdbExceptions.notSupported("CallableStatement");
    }

    @Override
    public CallableStatement prepareCall(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        throw ChdbExceptions.notSupported("CallableStatement");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() throws SQLException {
        synchronized (closeLock) {
            doClose();
        }
    }

    private void doClose() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;  // JDBC requires close() to be idempotent.
        }

        SQLException firstFailure = null;

        // Statements first, so their streams and batches are released before the connection
        // they were opened on. The shim keeps a strong reference from stream to connection,
        // so the other order would leak rather than crash -- but leaking is still wrong.
        for (ChdbStatement statement : openStatements.toArray(new ChdbStatement[0])) {
            try {
                statement.closeOnConnectionClose();
            } catch (SQLException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        openStatements.clear();

        // Freed unconditionally: the holder may be a thread that is no longer running, and a
        // permit left behind would make the (already closed) Connection look busy forever.
        statementSlot.release();

        try {
            ChdbNative.closeConnection(handle);
        } catch (ChdbNativeException e) {
            SQLException wrapped = ChdbExceptions.wrap("Failed to close the chDB connection", e);
            if (firstFailure == null) {
                firstFailure = wrapped;
            } else {
                firstFailure.addSuppressed(wrapped);
            }
        } finally {
            // Released even if the native close failed: the handle is gone from the registry
            // either way, so keeping the path pinned would strand the JVM.
            StoragePathRegistry.release(url);
            ShutdownCleanup.unregister(this);
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("timeout must not be negative", "22023");
        }
        if (closed.get()) {
            return false;
        }
        // A real round trip through the engine, not just a flag check: that is what a pool's
        // validation query is for, and SELECT 1 is the cheapest statement there is.
        try (Statement statement = createStatement();
                java.sql.ResultSet rs = statement.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ transactions: none

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (!autoCommit) {
            throw ChdbExceptions.notSupported(
                    "Disabling auto-commit (chDB has no transactions, so every statement is"
                            + " committed as it executes)");
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void commit() throws SQLException {
        throw ChdbExceptions.notSupported("commit() (chDB has no transactions)");
    }

    @Override
    public void rollback() throws SQLException {
        throw ChdbExceptions.notSupported("rollback() (chDB has no transactions)");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw ChdbExceptions.notSupported("rollback(Savepoint) (chDB has no transactions)");
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw ChdbExceptions.notSupported("Savepoints (chDB has no transactions)");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw ChdbExceptions.notSupported("Savepoints (chDB has no transactions)");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw ChdbExceptions.notSupported("Savepoints (chDB has no transactions)");
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        if (level != Connection.TRANSACTION_NONE) {
            throw ChdbExceptions.notSupported(
                    "Transaction isolation level " + level + " (chDB reports TRANSACTION_NONE)");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return Connection.TRANSACTION_NONE;
    }

    // ------------------------------------------------------------------ metadata and settings

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new ChdbDatabaseMetaData(this);
    }

    /**
     * Recorded and reported back, but not enforced.
     *
     * <p>JDBC describes read-only as a hint for optimization, and chDB has no session-level
     * read-only mode to map it onto. Rejecting writes in the driver instead would need the
     * driver to classify every statement and would still miss what a table function can do,
     * so the honest behaviour is to keep the flag and say so.
     */
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return catalog;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
        // ClickHouse's "database" is JDBC's schema; USE is how a session switches.
        if (schema != null && !schema.isEmpty()) {
            try (Statement statement = createStatement()) {
                statement.execute("USE " + quoteIdentifier(schema));
            }
        }
        this.schema = schema;
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        if (schema != null) {
            return schema;
        }
        try (Statement statement = createStatement();
                java.sql.ResultSet rs = statement.executeQuery("SELECT currentDatabase()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Backtick-quotes an identifier, doubling any backtick inside it. */
    static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        if (holdability != java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw ChdbExceptions.notSupported(
                    "Holdability " + holdability + " (there are no transactions to hold across)");
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        // The C ABI surfaces errors but not warnings, so there is never anything to report.
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public void setClientInfo(String name, String value) {
        clientInfo.put(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (properties == null) {
            return;
        }
        for (String name : properties.stringPropertyNames()) {
            clientInfo.put(name, properties.getProperty(name));
        }
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return clientInfo.get(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : clientInfo.entrySet()) {
            if (entry.getValue() != null) {
                properties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return properties;
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();
        if (milliseconds < 0) {
            throw new SQLException("timeout must not be negative", "22023");
        }
        // Recorded for getNetworkTimeout, but there is no network: chDB runs in this process.
        // Statement.setQueryTimeout is the timeout that does something.
        this.networkTimeoutMillis = milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeoutMillis;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        // JDBC's abort is "close it now, from another thread". close() already tolerates
        // that: it closes statements, which cancel their in-flight queries.
        close();
    }

    // ------------------------------------------------------------------ type factories

    @Override
    public Clob createClob() throws SQLException {
        throw ChdbExceptions.notSupported("Clob");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw ChdbExceptions.notSupported("Blob");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw ChdbExceptions.notSupported("NClob");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw ChdbExceptions.notSupported("SQLXML");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw ChdbExceptions.notSupported("Array");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw ChdbExceptions.notSupported("Struct");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return Collections.emptyMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
        if (map != null && !map.isEmpty()) {
            throw ChdbExceptions.notSupported("Custom type maps");
        }
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        // No escape-syntax translation: chDB speaks ClickHouse SQL and the driver passes it
        // through unchanged, so the native form is what the caller wrote.
        return sql;
    }

    // ------------------------------------------------------------------ wrapper

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName(), "0A000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
