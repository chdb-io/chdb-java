package org.chdb.jdbc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces {@link java.sql.Statement#setQueryTimeout(int)} by cancelling the engine.
 *
 * <p>Work plan section 5.8 asks for a timeout that maps onto an explicit cancel rather than
 * only giving up on the Java side. Abandoning the wait would leave the engine grinding
 * through a query nobody is reading, holding memory and CPU until it finished on its own.
 *
 * <h2>What the clock covers</h2>
 * From {@code execute} until the result set is closed or exhausted -- not just the call that
 * opened the stream. For a streaming statement almost all the work happens in {@code next()},
 * so a timeout that stopped at open would expire during the cheapest part and never fire
 * during the expensive one.
 *
 * <h2>What it cannot cover</h2>
 * Statements with no result set run through {@code chdb_query_n}, which is synchronous and
 * has no cancellation handle in the C ABI. There is nothing to interrupt, so the timeout does
 * not apply to DDL or DML, and {@code ChdbStatement} does not arm one for them.
 *
 * <p>Nor can it interrupt the call that <em>opens</em> a result set, for the same reason: every
 * cancel the C ABI exports takes a result or stream handle, and that handle is what the open is
 * still producing. So the cancel this fires during an open reaches nothing. How long that
 * window lasts depends on the statement, not on the driver -- milliseconds for a SELECT that
 * emits as it scans, the whole query for a full aggregate or an {@code ORDER BY} without a
 * {@code LIMIT}, and the whole statement for anything on the materialized Arrow route. {@code
 * ChdbStatement} therefore also treats an expired timeout as a deadline once the open returns:
 * the work is already spent, but the caller is told, rather than handed a result set that
 * arrived after the time it allowed.
 */
final class QueryTimeout {

    /** A disarmed timeout: nothing scheduled, {@link #stop()} does nothing. */
    static final QueryTimeout NONE = new QueryTimeout();

    /**
     * One daemon thread for every timeout in the JVM.
     *
     * <p>Daemon so it never holds up JVM exit, and shared because a timeout fires rarely --
     * a thread per statement would cost far more than the scheduling it does.
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "chdb-query-timeout");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final AtomicBoolean expired = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> future;

    private QueryTimeout() {
    }

    /**
     * Arms a timeout for one execution of {@code statement}.
     *
     * <p>The scheduled task does not cancel the statement itself. It hands both this timeout
     * and {@code execution} back to {@link ChdbStatement#queryTimeoutFired(QueryTimeout, long)},
     * which drops it if that execution is already over -- see {@link #stop()} for why a task
     * can still be on its way after the execution that armed it has finished.
     *
     * @param seconds 0 or less for no timeout
     * @param execution the statement's execution counter at the moment of arming
     * @return the armed timeout, or {@link #NONE} when there is nothing to schedule
     */
    static QueryTimeout start(ChdbStatement statement, int seconds, long execution) {
        if (seconds <= 0) {
            return NONE;
        }
        QueryTimeout timeout = new QueryTimeout();
        timeout.future =
                SCHEDULER.schedule(
                        () -> statement.queryTimeoutFired(timeout, execution),
                        seconds,
                        TimeUnit.SECONDS);
        return timeout;
    }

    /** Whether the timeout fired, so a cancel can be reported as a timeout. */
    boolean expired() {
        return expired.get();
    }

    /**
     * Records that this timeout's deadline passed while its own execution was still running.
     *
     * <p>Set by {@link ChdbStatement} rather than by the task, because whether the deadline
     * belongs to the execution now in flight is the statement's question, not the timer's.
     */
    void markExpired() {
        expired.set(true);
    }

    /**
     * Disarms the timeout.
     *
     * <p>Idempotent, and safe after it has already fired -- but <em>not</em> a guarantee that
     * the task will not run. This is {@code Future.cancel(false)}: a task the scheduler has
     * already begun runs to completion, and this call does not wait for it. So a timeout
     * stopped at the instant its deadline passed can still deliver, after the execution that
     * armed it has finished and the next one has started on the same statement. Interrupting
     * instead ({@code cancel(true)}) would not fix that -- the task does no interruptible
     * waiting -- and waiting for it would put an unbounded pause in {@code ResultSet.close()}.
     * Which is why the task carries an execution number and {@link ChdbStatement} checks it.
     */
    void stop() {
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
