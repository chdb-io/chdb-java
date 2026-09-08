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
     * Arms a timeout that cancels {@code statement} after {@code seconds}.
     *
     * @param seconds 0 or less for no timeout
     * @return the armed timeout, or {@link #NONE} when there is nothing to schedule
     */
    static QueryTimeout start(ChdbStatement statement, int seconds) {
        if (seconds <= 0) {
            return NONE;
        }
        QueryTimeout timeout = new QueryTimeout();
        timeout.future =
                SCHEDULER.schedule(
                        () -> {
                            timeout.expired.set(true);
                            try {
                                statement.cancel();
                            } catch (Exception ignored) {
                                // The statement may have finished or been closed in the
                                // meantime. Cancel is best-effort by definition, and there is
                                // no caller on this thread to report a failure to.
                            }
                        },
                        seconds,
                        TimeUnit.SECONDS);
        return timeout;
    }

    /** Whether the timeout fired, so a cancel can be reported as a timeout. */
    boolean expired() {
        return expired.get();
    }

    /** Disarms the timeout. Idempotent, and safe after it has already fired. */
    void stop() {
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
