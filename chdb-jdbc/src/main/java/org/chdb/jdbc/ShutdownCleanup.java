package org.chdb.jdbc;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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
 */
final class ShutdownCleanup {

    /** Set to {@code false} to not install the hook. */
    static final String PROP_ENABLED = "chdb.shutdownHook";

    /**
     * Open connections, weakly held.
     *
     * <p>Weak so the registry never keeps a connection alive that the application has dropped;
     * a collected one had no live stream to close anyway. Synchronized rather than concurrent
     * because it is touched twice per connection, not per query.
     */
    private static final Set<ChdbConnection> OPEN =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private static volatile boolean hookInstalled;
    private static volatile boolean shuttingDown;

    private ShutdownCleanup() {
    }

    /** Notes a connection as open, installing the hook on first use. */
    static void register(ChdbConnection connection) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "true"))) {
            return;
        }
        installHook();
        OPEN.add(connection);
    }

    static void unregister(ChdbConnection connection) {
        OPEN.remove(connection);
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

        // Copied out first: close() calls unregister(), which would otherwise mutate the set
        // being iterated.
        List<ChdbConnection> connections;
        synchronized (OPEN) {
            connections = new ArrayList<>(OPEN);
        }

        for (ChdbConnection connection : connections) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (Throwable ignored) {
                // A shutdown hook has nobody to report to, and a failure here must not stop the
                // remaining connections from being closed. The worst case is the abort this
                // hook exists to avoid, which is no worse than not having tried.
            }
        }
    }
}
