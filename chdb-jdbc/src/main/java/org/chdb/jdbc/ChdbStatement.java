package org.chdb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.chdb.internal.ChdbNative;
import org.chdb.internal.ChdbNativeException;

/**
 * Executes one statement at a time on a {@link ChdbConnection}.
 *
 * <h2>Three routes, not two</h2>
 * A statement with a result set the engine will stream goes through {@code
 * chdb_stream_query_arrow_n} and yields a {@link ChdbResultSet} that holds one batch at a
 * time. A statement with a result set the engine refuses to stream -- {@code SHOW}, {@code
 * DESCRIBE}, {@code EXPLAIN}, {@code EXISTS}, {@code CHECK}, none of which parse as a SELECT
 * pipeline -- goes through {@code chdb_query_arrow_n}, which delivers the same Arrow C Data
 * Interface materialized, and produces a {@link ChdbResultSet} indistinguishable from the
 * first. Everything else -- DDL, DML, session control -- goes through {@code chdb_query_n}.
 * {@link StatementShape} decides which, from the engine's classifier where available.
 *
 * <p>The route is decided once, before execution, and no statement is ever executed twice:
 * {@link StatementShape} documents why a failed stream open is reported rather than retried
 * on the materialized route.
 *
 * <h2>Cancellation</h2>
 * {@link #cancel()} is the one method that may be called from another thread while this one
 * is executing, and it deliberately does not take the connection's statement slot -- taking it
 * would mean waiting for the query it is meant to interrupt. It reads the in-flight stream
 * handle from an {@link AtomicReference} and asks the engine to cancel it.
 *
 * <p>{@link #setQueryTimeout(int)} is built on the same mechanism: a timer thread calls the
 * same cancel path, so a timeout actually stops the engine rather than only abandoning the
 * Java-side wait (work plan section 5.8).
 *
 * <p>There is one window in which neither can interrupt anything, because there is no handle
 * to cancel yet: the call that opens the result set. {@link
 * #checkDeadlineSurvivedTheOpen(String)} is what keeps that window from turning a timeout into
 * a late success.
 */
public class ChdbStatement implements Statement {

    /**
     * chDB streams in blocks the engine chooses, so this is not a fetch size the driver can
     * enforce. Recorded for {@code getFetchSize} and otherwise inert; saying so beats
     * pretending to honour it.
     */
    private static final int DEFAULT_FETCH_SIZE = 0;

    final ChdbConnection connection;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** The stream currently executing, for {@link #cancel()}. Null when nothing is in flight. */
    private final AtomicReference<Long> inFlightStream = new AtomicReference<>(null);

    private ChdbResultSet currentResultSet;
    /**
     * Armed while a streaming statement is in flight and disarmed when its result set closes
     * or is exhausted, so the clock covers the fetches rather than only the open.
     */
    private QueryTimeout activeTimeout = QueryTimeout.NONE;
    private long currentUpdateCount = -1;
    private int queryTimeoutSeconds;
    private int fetchSize = DEFAULT_FETCH_SIZE;
    private int maxRows;
    private boolean poolable = true;
    private boolean closeOnCompletion;

    ChdbStatement(ChdbConnection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (!executeInternal(sql, Collections.<String>emptyList(), Collections.<String>emptyList(), true)) {
            throw new SQLException(
                    "executeQuery() requires a statement that returns a result set, but "
                            + describeStatement(sql)
                            + " does not. Use executeUpdate() or execute() instead.",
                    "07500");
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        long count = executeLargeUpdate(sql);
        // JDBC's int form saturates rather than overflowing; the long form is exact.
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        if (executeInternal(sql, Collections.<String>emptyList(), Collections.<String>emptyList(), false)) {
            // Close the result set we were handed: leaving it open would pin a stream that
            // the caller has no way to reach.
            closeCurrentResultSet();
            throw new SQLException(
                    "executeUpdate() requires a statement that does not return a result set, but "
                            + describeStatement(sql)
                            + " does. Use executeQuery() instead.",
                    "07500");
        }
        return currentUpdateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return executeInternal(sql, Collections.<String>emptyList(), Collections.<String>emptyList(), null);
    }

    /**
     * The single execution path for every {@code execute*} entry point.
     *
     * @param expectResultSet {@code TRUE} to require one, {@code FALSE} to require none,
     *     {@code null} to accept whatever the statement is
     * @return whether a result set is now current
     */
    boolean executeInternal(
            String sql, List<String> parameterNames, List<String> parameterValues, Boolean expectResultSet)
            throws SQLException {
        checkOpen();
        connection.checkOpen();

        // Any previous result set of this statement is released before the slot is taken,
        // because closing it is what frees the slot the previous execution held.
        closeCurrentResultSet();
        currentUpdateCount = -1;
        cancelled.set(false);

        // Held until the result set closes for a streaming statement, and only for the call
        // itself for one with no result set. The engine runs one statement per connection at a
        // time, and its fetches count.
        connection.statementSlot().acquire(sql);
        boolean handedOff = false;
        try {
            StatementShape.Route route = route(sql);
            boolean producesResultSet = route != StatementShape.Route.NO_RESULT_SET;
            if (expectResultSet != null && expectResultSet && !producesResultSet) {
                return false;
            }

            if (producesResultSet) {
                // Armed before the open, because the open performs the first fetch, and
                // disarmed by the result set rather than here -- the fetches that follow are
                // where the time goes.
                activeTimeout = QueryTimeout.start(this, queryTimeoutSeconds);
                try {
                    boolean opened = openStream(route, sql, parameterNames, parameterValues);
                    if (opened) {
                        // The result set now owns the slot and releases it when it closes.
                        handedOff = true;
                        checkDeadlineSurvivedTheOpen(sql);
                    }
                    return opened;
                } catch (RuntimeException | SQLException e) {
                    stopTimeout();
                    throw e;
                }
            }
            // No timeout for a statement with no result set: chdb_query_n is synchronous and
            // the C ABI offers no way to interrupt it, so arming a timer would only produce a
            // cancel that does nothing while the caller keeps waiting.
            runMaterialized(sql, parameterNames, parameterValues);
            return false;
        } finally {
            if (!handedOff) {
                connection.statementSlot().release();
            }
        }
    }

    /**
     * Fails the execution if the query timeout expired, or {@link #cancel()} was called, while
     * the open was in flight.
     *
     * <h2>Why this is needed at all</h2>
     * {@link #cancel()} works by handing the in-flight stream handle to the engine -- and
     * there is no handle until the call that creates it returns. Every exported cancel in the
     * C ABI takes a result or stream handle ({@code chdb_stream_cancel_query}, {@code
     * chdb_streaming_cancel_query}, {@code chdb_stream_cancel_insert}); there is no
     * connection-level cancel. So the open is uninterruptible on both routes, and a timer that
     * fires inside it finds {@code inFlightStream} still null and returns having done nothing.
     *
     * <p>Left there, the caller got the worst of both worlds: {@code executeQuery} came back
     * <em>successfully</em>, with a usable result set, long after the deadline it set. Measured
     * on v26.7.0 before this check: {@code setQueryTimeout(1)} on {@code SELECT
     * max(sipHash64(number)) FROM numbers(2000000000)} returned a working result set after
     * 12.65 seconds.
     *
     * <h2>How long that window is</h2>
     * On the streaming route it is the pipeline init plus the first batch, which for a SELECT
     * that emits as it scans is milliseconds -- but for a full aggregate, a {@code GROUP BY} or
     * an {@code ORDER BY} without a {@code LIMIT} there is no first batch until the whole scan
     * is done, so the window is the entire query. On the materialized route it is always the
     * entire statement, because {@code chdb_query_arrow_n} runs it to completion before
     * returning.
     *
     * <h2>What the caller gets instead</h2>
     * A deadline, not an interrupt. The statement has already run to completion by the time
     * this is reached and the engine cannot be told to stop, so the work is spent either way --
     * but reporting {@link SQLTimeoutException} is what a caller can act on, and it keeps
     * {@code setQueryTimeout} from being a setting that silently does nothing. The result set
     * is closed first, so the stream and the connection's statement slot are released rather
     * than pinned by a result nobody can reach.
     */
    private void checkDeadlineSurvivedTheOpen(String sql) throws SQLException {
        boolean expired = activeTimeout.expired();
        if (!expired && !cancelled.get()) {
            return;
        }
        // Releases the stream and the statement slot; the caller set handedOff before calling
        // here, so this close is the one release of that slot.
        closeCurrentResultSet();
        if (expired) {
            throw new SQLTimeoutException(
                    "The query exceeded the statement's query timeout of "
                            + queryTimeoutSeconds
                            + "s while "
                            + describeStatement(sql)
                            + " was still being executed. The engine could not be interrupted:"
                            + " the chDB C ABI has no cancel that applies before a query returns"
                            + " a handle, so the statement ran to completion and its result was"
                            + " discarded.",
                    "57014");
        }
        throw new SQLException(
                "The statement was cancelled while "
                        + describeStatement(sql)
                        + " was still being executed. The engine could not be interrupted before"
                        + " the query returned a handle, so it ran to completion and its result"
                        + " was discarded.",
                "57014",
                394);
    }

    private StatementShape.Route route(String sql) throws SQLException {
        int[] analysis;
        try {
            // Null when the engine predates chdb_classify_query_n, which the v26.7.2-rc.2
            // baseline does not -- but a loader pointed at another libchdb still can, in which
            // case StatementShape falls back to its keyword scan.
            analysis = ChdbNative.classifyQuery(connection.handle(), Utf8.encode(sql));
        } catch (ChdbNativeException e) {
            // A statement the parser rejects will fail on execution too, with a better
            // message than anything this classification step could produce.
            analysis = null;
        }
        return StatementShape.route(analysis, sql);
    }

    private boolean openStream(
            StatementShape.Route route,
            String sql,
            List<String> parameterNames,
            List<String> parameterValues)
            throws SQLException {
        ChdbUrl url = connection.chdbUrl();
        boolean lowCardinalityAsDictionary =
                url.booleanProperty(ChdbUrl.PROP_LOW_CARDINALITY_AS_DICTIONARY, false);
        boolean unsupportedAsBinary = url.booleanProperty(ChdbUrl.PROP_UNSUPPORTED_AS_BINARY, false);
        boolean stringAsString = url.booleanProperty(ChdbUrl.PROP_STRING_AS_STRING, true);

        boolean materialize = route == StatementShape.Route.MATERIALIZED_RESULT_SET;
        if (materialize && !parameterNames.isEmpty()) {
            // The engine exports chdb_query_arrow_n but no _with_params_n variant of it, so
            // there is no entry point that both accepts this statement and binds parameters.
            // Reported rather than worked around: interpolating the values into the SQL is
            // the injection this driver's server-side binding exists to avoid, and running
            // the statement with the bindings dropped would answer the wrong question.
            throw new SQLException(
                    "Server-side parameters are not supported for "
                            + describeStatement(sql)
                            + ", because the engine has no parameter-binding form of the Arrow"
                            + " entry point that accepts it (chdb_query_arrow_with_params_n is"
                            + " not exported by engine "
                            + ChdbNative.engineVersion()
                            + "). Use a Statement with the value written into the SQL, or a"
                            + " query over system.tables / system.columns, which is a SELECT and"
                            + " does take parameters.",
                    "0A000");
        }

        long stream;
        try {
            stream =
                    materialize
                            ? ChdbNative.streamOpenMaterialized(
                                    connection.handle(),
                                    Utf8.encode(sql),
                                    lowCardinalityAsDictionary,
                                    unsupportedAsBinary,
                                    stringAsString)
                            : ChdbNative.streamOpen(
                                    connection.handle(),
                                    Utf8.encode(sql),
                                    Utf8.encodeAll(parameterNames),
                                    Utf8.encodeAll(parameterValues),
                                    lowCardinalityAsDictionary,
                                    unsupportedAsBinary,
                                    stringAsString);
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Query failed", e);
        }

        inFlightStream.set(stream);
        try {
            currentResultSet = new ChdbResultSet(this, stream, maxRows);
        } catch (RuntimeException | SQLException e) {
            // The stream is ours until a ResultSet takes ownership of it.
            safeCloseStream(stream);
            inFlightStream.compareAndSet(stream, null);
            throw e;
        }
        return true;
    }

    private void runMaterialized(String sql, List<String> parameterNames, List<String> parameterValues)
            throws SQLException {
        long result;
        try {
            result =
                    ChdbNative.query(
                            connection.handle(),
                            Utf8.encode(sql),
                            // The payload is discarded for a statement with no result set, so
                            // the format only has to be one the engine accepts.
                            Utf8.encode("CSV"),
                            Utf8.encodeAll(parameterNames),
                            Utf8.encodeAll(parameterValues));
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Statement failed", e);
        }

        try {
            long written = ChdbNative.resultRowsWritten(result);
            // rows_written is the engine's own count of what an INSERT wrote, including rows
            // materialized views wrote downstream. For DDL it is 0, which JDBC also uses for
            // "nothing to report", so the two cases coincide without a special case.
            currentUpdateCount = written;
        } finally {
            try {
                ChdbNative.destroyResult(result);
            } catch (ChdbNativeException ignored) {
                // Destroying a result cannot fail in a way the caller can act on, and the
                // statement itself succeeded; reporting this would mask that.
            }
        }
    }

    private String describeStatement(String sql) {
        String keyword = StatementShape.leadingKeyword(sql);
        return keyword == null ? "this statement" : "a " + keyword + " statement";
    }

    // ------------------------------------------------------------------ cancellation

    /**
     * Asks the engine to abandon the statement in flight.
     *
     * <p>Called from another thread, by design, and therefore without the connection's
     * execution lock. Safe to call when nothing is running, or after the stream has already
     * ended: the shim treats cancelling a finished stream as a no-op.
     */
    @Override
    public void cancel() throws SQLException {
        Long stream = inFlightStream.get();
        cancelled.set(true);
        if (stream == null) {
            return;
        }
        try {
            ChdbNative.streamCancel(connection.handle(), stream);
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Failed to cancel the statement", e);
        }
    }

    /** Whether {@link #cancel()} was called for the statement currently or last in flight. */
    boolean wasCancelled() {
        return cancelled.get();
    }

    /** Whether the cancel that ended the statement came from an expired query timeout. */
    boolean timedOut() {
        return activeTimeout.expired();
    }

    void clearInFlight(long stream) {
        inFlightStream.compareAndSet(stream, null);
    }

    /** Disarms the query timeout. Called by the result set once it closes or hits its end. */
    void stopTimeout() {
        activeTimeout.stop();
    }

    // ------------------------------------------------------------------ results

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        long count = getLargeUpdateCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return currentResultSet != null ? -1 : currentUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        currentUpdateCount = -1;
        // A chDB statement produces at most one result. Multiple result sets would need
        // multi-statement execution, which the driver does not do.
        return false;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();
        if (current == Statement.KEEP_CURRENT_RESULT) {
            throw ChdbExceptions.notSupported("KEEP_CURRENT_RESULT (a statement has one result)");
        }
        return getMoreResults();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    void resultSetClosed(ChdbResultSet resultSet) {
        stopTimeout();
        // The slot was handed to this result set when it opened; closing it is what lets the
        // next statement on this connection run.
        connection.statementSlot().release();
        if (currentResultSet == resultSet) {
            currentResultSet = null;
        }
        if (closeOnCompletion) {
            try {
                close();
            } catch (SQLException ignored) {
                // closeOnCompletion is a convenience; a failure closing the statement must
                // not replace whatever the caller was doing with the result set.
            }
        }
    }

    private void closeCurrentResultSet() throws SQLException {
        ChdbResultSet resultSet = currentResultSet;
        currentResultSet = null;
        if (resultSet != null) {
            resultSet.close();
        }
    }

    private void safeCloseStream(long stream) {
        try {
            ChdbNative.streamClose(stream);
        } catch (ChdbNativeException ignored) {
            // Already reporting a more useful failure to the caller.
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            closeCurrentResultSet();
        } finally {
            connection.unregister(this);
        }
    }

    /** Closes without touching the connection's statement set, which is being iterated. */
    void closeOnConnectionClose() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeCurrentResultSet();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    void checkOpen() throws SQLException {
        if (closed.get()) {
            throw ChdbExceptions.closed("Statement");
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    // ------------------------------------------------------------------ knobs

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeoutSeconds;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw new SQLException("query timeout must not be negative", "22023");
        }
        this.queryTimeoutSeconds = seconds;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException("maxRows must not be negative", "22023");
        }
        this.maxRows = max;
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        return getMaxRows();
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        if (max > Integer.MAX_VALUE) {
            throw ChdbExceptions.notSupported("More than Integer.MAX_VALUE max rows");
        }
        setMaxRows((int) max);
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw new SQLException("fetchSize must not be negative", "22023");
        }
        // Recorded but inert: batch size is the engine's block size, which the driver does not
        // control. Throwing here would break frameworks that set a fetch size as a matter of
        // course, and honouring it is not possible.
        this.fetchSize = rows;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw ChdbExceptions.notSupported(
                    "Fetch direction " + direction + " (result sets are forward-only)");
        }
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max != 0) {
            throw ChdbExceptions.notSupported("Truncating values with setMaxFieldSize");
        }
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
        // There is no escape processing to enable or disable; SQL is passed through verbatim.
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw ChdbExceptions.notSupported("Named cursors");
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        this.poolable = poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        this.closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    // ------------------------------------------------------------------ batch: not in V1

    @Override
    public void addBatch(String sql) throws SQLException {
        throw ChdbExceptions.notSupported("Batch updates");
    }

    @Override
    public void clearBatch() throws SQLException {
        throw ChdbExceptions.notSupported("Batch updates");
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw ChdbExceptions.notSupported("Batch updates");
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        throw ChdbExceptions.notSupported("Batch updates");
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw ChdbExceptions.notSupported("Generated keys");
        }
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw ChdbExceptions.notSupported("Generated keys");
        }
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw ChdbExceptions.notSupported("Generated keys");
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
