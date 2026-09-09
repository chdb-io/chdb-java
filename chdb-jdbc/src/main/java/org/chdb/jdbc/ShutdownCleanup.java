package org.chdb.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Closes connections the application forgot, at JVM shutdown.
 *
 * <h2>What this prevents</h2>
 * A JVM that exits with a streaming result set still open aborts inside the engine rather than
 * exiting:
 *
 * <pre>
 * libc++ Hardening assertion !empty() failed: front() called on an empty vector
 *   contrib/llvm-project/libcxx/include/__vector/vector.h:436
 * </pre>
 *
 * Reproducible on engine 26.7.0, and narrow: exiting with a {@code Connection} open is fine,
 * and so is exiting after closing the connection while a stream is still open, because that
 * closes the stream on the way. Only an open <em>stream</em> at exit does it. The process dies
 * with SIGABRT and a stack that names ClickHouse internals, which tells the application nothing
 * about the result set it leaked.
 *
 * <p>Closing connections in a shutdown hook covers it: {@code ChdbConnection.close()} closes
 * its statements, which close their result sets, which is the state the engine tolerates.
 *
 * <h2>What it is not</h2>
 * Not a substitute for closing things. It runs at shutdown, so a leak still holds engine memory
 * and a statement slot for the life of the process; the hook only makes the exit clean. Use
 * try-with-resources.
 *
 * <p>The proper fix is {@code chdb_shutdown()}, which joins every engine thread before the host
 * tears itself down — exactly this problem, solved in the engine. It landed after the pinned
 * v26.7.0 baseline, so the driver resolves it optionally and it is absent here.
 *
 * <p>Disable with {@code -Dchdb.shutdownHook=false} if the host manages its own teardown and
 * does not want a hook it did not install.
 *
 * <h2>Concurrency</h2>
 * A shutdown hook runs concurrently with application threads, which the JVM does not stop
 * first. Everything here is written for that: registration can happen while the hook is
 * draining, and a connection can be mid-{@code close()} on another thread when the hook reaches
 * it. Both cases end with the stream closed, or the hook has not done its job.
 *
 * <p>What draining cannot fix is a connection opened after the hook has already returned —
 * from a competing shutdown hook, say. Nothing can: the hook would have to wait for a
 * connection that may never come. The budget below bounds how long it tries.
 */
final class ShutdownCleanup {

    /** Set to {@code false} to not install the hook. */
    static final String PROP_ENABLED = "chdb.shutdownHook";

    /**
     * Open connections, weakly held.
     *
     * <p>Weak so the registry never keeps a connection alive that the application has dropped;
     * a collected one had no live stream to close anyway.
     *
     * <p>Kept as the synchronized {@code Map} rather than a {@code newSetFromMap} view over it.
     * The mutex of a {@code Collections.synchronizedMap} is the wrapper object itself, and a set
     * view does not share it — so synchronizing on the set would leave the hook's iteration
     * unguarded against a concurrent {@link #register}, and the hook is the one thread that
     * must not die of a {@code ConcurrentModificationException}.
     */
    private static final Map<ChdbConnection, Boolean> OPEN =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * How long the hook keeps re-checking for connections opened while it was running.
     *
     * <p>Bounded by time rather than by a number of passes: a thread still opening connections
     * makes each pass short and there is no useful count to pick, whereas the thing actually
     * being protected is how long the JVM takes to exit. Five seconds is far longer than a real
     * teardown needs, and an application still connecting after that is one no hook can win
     * against — hanging would be the worse failure.
     */
    private static final long DRAIN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static volatile boolean hookInstalled;
    private static volatile boolean shuttingDown;

    private ShutdownCleanup() {
    }

    /** Notes a connection as open, installing the hook on first use. */
    static void register(ChdbConnection connection) {
        // Nothing here may fail a connection whose native handle is already open: the caller
        // is past the point where it can unwind cleanly, so a throw would leak the handle and
        // the storage-path binding. A safety net that breaks what it is catching is worse than
        // no net. The concrete case is a security manager denying the property read.
        try {
            if (!Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "true"))) {
                return;
            }
            installHook();
            synchronized (OPEN) {
                OPEN.put(connection, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // Registered or not, the connection itself is fine.
        }
    }

    static void unregister(ChdbConnection connection) {
        synchronized (OPEN) {
            OPEN.remove(connection);
        }
    }

    /**
     * Whether the JVM is shutting down and this hook is doing the closing.
     *
     * <p>{@code ChdbConnection.close()} reads it to skip work that only matters to a process
     * that keeps running, and to avoid waiting on anything.
     */
    static boolean isShuttingDown() {
        return shuttingDown;
    }

    private static void installHook() {
        if (hookInstalled) {
            return;
        }
        synchronized (ShutdownCleanup.class) {
            if (hookInstalled) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(ShutdownCleanup::closeAll, "chdb-shutdown"));
                hookInstalled = true;
            } catch (IllegalStateException | SecurityException e) {
                // Already shutting down, or a security manager forbids hooks. Neither is worth
                // failing a connection over: the hook is a safety net, not the mechanism.
                hookInstalled = true;
            }
        }
    }

    private static void closeAll() {
        shuttingDown = true;

        // Drained rather than snapshotted once. A thread that connects and opens a stream
        // after a single snapshot was taken would be missed, leaving exactly the state this
        // hook exists to prevent -- and shutdown hooks run alongside application threads, so
        // that race is ordinary rather than exotic.
        long deadline = System.nanoTime() + DRAIN_BUDGET_NANOS;
        while (true) {
            List<ChdbConnection> batch;
            synchronized (OPEN) {
                if (OPEN.isEmpty()) {
                    return;
                }
                batch = new ArrayList<>(OPEN.keySet());
                OPEN.clear();
            }

            // Outside the lock: close() reaches the engine, and holding the registry's monitor
            // across that would block every thread still trying to register or unregister.
            for (ChdbConnection connection : batch) {
                try {
                    // Unconditionally, rather than guarded by isClosed(). A connection whose
                    // close() is in flight on another thread already reports itself closed
                    // while its streams are still open; skipping it would let the hook finish,
                    // the JVM exit, and that thread be halted mid-close. close() is idempotent
                    // and blocks until an in-flight close finishes, so this waits instead.
                    connection.close();
                } catch (Throwable ignored) {
                    // A shutdown hook has nobody to report to, and a failure here must not stop
                    // the remaining connections from being closed. The worst case is the abort
                    // this hook exists to avoid, which is no worse than not having tried.
                }
            }

            if (System.nanoTime() - deadline >= 0) {
                return;
            }
        }
    }
}
