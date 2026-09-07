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

    private final Map<String, String> clientInfo = new LinkedHashMap<>();
    private volatile boolean readOnly;
    private volatile String catalog;
    private volatile String schema;
    private volatile int networkTimeoutMillis;

    ChdbConnection(ChdbUrl url) throws SQLException {
        this.url = url;

        try {
            // Loads libchdb and the shim, and opts out of chDB's signal handlers before the
            // first connect -- that opt-out resets the JVM's own SIGSEGV/SIGBUS/SIGILL/SIGFPE
            // handlers as a side effect, and the shim restores them (work plan section 5.6).
            NativeLibraryLoader.ensureLoaded();
            ChdbNative.protectHostSignalHandlers();
        } catch (UnsupportedPlatformException e) {
            throw ChdbExceptions.wrap("Cannot connect to " + url.url(), e);
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Cannot load the chDB native runtime", e);
        }

        StoragePathRegistry.acquire(url);
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
