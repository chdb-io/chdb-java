package org.chdb.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.chdb.internal.ChdbNative;

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
 * Still reproducible on engine 26.7.2-rc.2, and narrow: exiting with a {@code Connection} open
 * is fine, and so is exiting after closing the connection while a stream is still open, because
 * that closes the stream on the way. Only an open <em>stream</em> at exit does it. The process
 * dies with SIGABRT and a stack that names ClickHouse internals, which tells the application
 * nothing about the result set it leaked.
 *
 * <p>Closing connections in a shutdown hook covers it: {@code ChdbConnection.close()} closes
 * its statements, which close their result sets, which is the state the engine tolerates. Once
 * they are closed, {@link #stopEngine()} asks the engine to join its own threads.
 *
 * <h2>What it is not</h2>
 * Not a substitute for closing things. It runs at shutdown, so a leak still holds engine memory
 * and a statement slot for the life of the process; the hook only makes the exit clean. Use
 * try-with-resources.
 *
 * <p>Nor is it a workaround waiting to be deleted in favour of {@code chdb_shutdown()}, which
 * is how it was written up when the engine did not export that call yet. The baseline now does
 * export it and it is called — but it declines to do anything while any connection is open, so
 * it needs the connections closed first, which is this class. See {@link #stopEngine()} for
 * what each of the two actually buys, measured.
 *
 * <p>Disable with {@code -Dchdb.shutdownHook=false} if the host manages its own teardown and
 * does not want a hook it did not install. That also turns off the {@code chdb_shutdown()}
 * call, so a host that wants the engine's threads joined has to make the call itself.
 *
 * <h2>Concurrency</h2>
 * A shutdown hook runs concurrently with application threads, which the JVM does not stop
 * first. Everything here is written for that: registration can happen while the hook is
 * draining, a connection can be mid-{@code close()} on another thread when the hook reaches
 * it, and a thread can be starting a statement on a connection the hook is about to reach.
 * The first two end with the stream closed, or the hook has not done its job. The third is
 * settled by {@link ExecutionGate}, and settled rather than raced: the hook takes a connection
 * with a compare-and-set that succeeds only while nothing is starting on it, so there is no
 * "look, then close" window for a statement to arrive in.
 *
 * <p>The hook does not stop at the first empty registry either. A thread part-way through
 * {@code connect()} has not registered yet, so the drain counts those separately and waits
 * while the count is non-zero; and any close or registration it does see restarts a quiet
 * period. The constants below bound how long it tries.
 *
 * <h2>What it cannot do</h2>
 * A connection opened long after everything went quiet — from a competing shutdown hook, say —
 * is unreachable. Nothing can reach it: the hook would have to wait for a connection that may
 * never come.
 *
 * <h2>Connections it declines to close</h2>
 * <strong>A connection whose statement is executing is left alone.</strong> Closing one is not
 * something the engine tolerates, and this hook used to do it — which made the hook the cause
 * of the very assertion quoted at the top of this class. Measured on engine 26.7.2-rc.2, macOS
 * arm64, two threads on a two-billion-row aggregate with {@code main} returning while they are
 * in flight:
 *
 * <pre>
 *   hook on, closing them (what it did)   21 aborts in 60  (front() on an empty vector)
 *   hook off                               0 aborts in 60
 *   hook on, skipping them (what it does)  0 aborts in 80
 * </pre>
 *
 * <p>The runs that did not abort were not free either: {@code chdb_close_conn()} on a
 * connection with a query running <em>blocks</em> until the query finishes, so the hook held
 * the JVM open for as long as the query — 34 s for four threads on five billion rows, 79 s for
 * a longer one — against 1.1 s now. {@link #DRAIN_BUDGET_NANOS} cannot bound that: the budget
 * is checked between passes and a close already under way is not interruptible.
 *
 * <p>Skipping is safe because the state it leaves behind is the one the engine is happy with —
 * a connection open, with no stream of its own yet, which the second row above shows exits
 * cleanly. It is not the leaked stream this class exists for: that thread is parked, not inside
 * the engine, so it is closed as before. The two are told apart by whether a thread is inside a
 * native call that starts a statement, not by the statement slot, which a leaked stream holds
 * as well.
 *
 * <p>The distinction is <em>claimed</em>, not inspected. Asking a connection whether a
 * statement is executing and then closing it would leave the abort reachable in the window
 * between the two, which for a hook running alongside live application threads is an ordinary
 * interleaving rather than an exotic one. {@link ExecutionGate} makes the question and the
 * answer one compare-and-set, and tells the loser: the hook skips the connection, or the
 * application thread gets {@code SQLException} with SQLSTATE {@code 08003} naming the shutdown.
 *
 * <p><strong>One abort in this shape is still not the driver's to fix.</strong> Rarely, and in
 * bursts that track machine load, a JVM halting with a thread inside the engine dies in C++
 * exit-time destructors instead: {@code mutex lock failed: Invalid argument}. It appears at the
 * same rate whether the hook runs or not — 2 of 80 against 3 of 80 on one build, and 1 of 80
 * with the hook on after the claim went in — so no hook reaches it, and
 * {@code -Dchdb.shutdownHook=false} does not avoid it. {@code docs/upstream-findings.md} §9 has
 * it.
 *
 * <p>What skipping costs: {@link #stopEngine()} declines while any connection is open, so a
 * process exiting with a query in flight does not get the engine's threads joined. Measured,
 * that changes no exit code — see {@link #stopEngine()} — and the drain does give such a
 * connection another look on each pass, so a query that ends inside the drain's remaining time
 * is closed after all. An application that wants the guarantee has to stop its query threads
 * before exiting; that has not changed, and {@code docs/unsupported.md} says so.
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
     * Connect attempts that have not reached {@link #register} yet.
     *
     * <p>The registry alone cannot answer "is anything still coming?". {@code
     * ChdbConnection}'s constructor opens the native handle before it registers, so between
     * those two points a live engine handle exists and {@link #OPEN} is empty — and the first
     * connection in a process is the most exposed, because the thread that installs the hook
     * is the thread that has not registered yet. The drain used to look, find nothing and
     * return, which left that connection's stream open at exit: the abort this class exists
     * to prevent.
     *
     * <p>Counted rather than inferred from an empty registry, because the alternative — treat
     * the start of the drain as activity and always wait out {@link #QUIET_PERIOD_NANOS} —
     * makes every clean exit pay up to 400 ms for a connection that is usually not there. A
     * counter waits only when a connect is known to be in progress; measured, a clean exit
     * still pays nothing (see {@code ProcessLifecycleIT}).
     *
     * <p>Incremented before {@code chdb_connect()} and decremented after {@code register()},
     * in a {@code finally} so a connect that throws cannot leave it raised — a leaked count
     * would make every later JVM exit in the process wait out {@link #DRAIN_BUDGET_NANOS}.
     * The order inside {@code register()} matters too: the connection is in {@link #OPEN}
     * before the count drops, so there is no instant in which neither says it exists.
     */
    private static final AtomicInteger CONNECTS_IN_FLIGHT = new AtomicInteger();

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

    /**
     * How long the registry must stay quiet before the hook accepts that it is done.
     *
     * <p>It is not enough to wait only after seeing a late registration: on a machine where
     * closing is faster than connecting, the hook drains what it found and is gone before the
     * first late registration happens. So any activity at all — a close or a registration —
     * restarts this clock.
     *
     * <p>The other case an empty registry does not rule out — a thread part-way through
     * {@code connect()} that has not registered yet — is <em>not</em> covered by this clock,
     * and used to be missed entirely: with nothing closed and nothing registered there was no
     * activity to wait out, so the drain returned at once. {@link #CONNECTS_IN_FLIGHT} covers
     * that one by counting it instead of guessing at it.
     *
     * <p>A clean exit pays nothing for it. Every connection is already closed, so the hook's
     * first pass closes nothing, there is no activity to wait out, and it returns immediately.
     * The cost falls only on a process that leaked, which is the one that would otherwise
     * abort.
     */
    private static final long QUIET_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(400);

    /** How often to re-check while waiting out the quiet period. */
    private static final long POLL_MILLIS = 20;

    private static volatile boolean hookInstalled;
    private static volatile boolean shuttingDown;

    /**
     * When the hook last saw anything happen: a connection closed, or one registered during
     * shutdown. Unset until there is something to wait for.
     */
    private static volatile boolean sawActivity;
    private static volatile long lastActivityNanos;

    private ShutdownCleanup() {
    }

    /**
     * Notes that a thread is about to open a native handle it has not registered yet.
     *
     * <p>Must be paired with {@link #connectFinished()} from a {@code finally}, on every path
     * including a connect that throws. See {@link #CONNECTS_IN_FLIGHT}.
     */
    static void connectStarted() {
        CONNECTS_IN_FLIGHT.incrementAndGet();
    }

    /** The other half of {@link #connectStarted()}, whether the connect succeeded or not. */
    static void connectFinished() {
        CONNECTS_IN_FLIGHT.decrementAndGet();
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
            if (shuttingDown) {
                // Noted so the hook knows connections are still arriving and does not stop at
                // the first empty pass.
                noteActivity();
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
     * <p>Nothing reads it. It was added for a {@code ChdbConnection.close()} that would skip
     * work only a surviving process cares about, and that close path was never written —
     * noted here rather than deleted because the flag is also what {@link #register} uses to
     * decide whether a late registration counts as activity, and because a caller that does
     * want to ask is likely to arrive with the connection-level fix upstream (§9).
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

    private static void noteActivity() {
        lastActivityNanos = System.nanoTime();
        sawActivity = true;
    }

    private static void closeAll() {
        shuttingDown = true;
        drainOpenConnections();
        stopEngine();
    }

    /**
     * {@code chdb_shutdown()}: joins every thread the engine started, once no connection is
     * left open.
     *
     * <p>Second, not instead. Measured on engine 26.7.2-rc.2 with the hook off, a JVM that
     * leaks a streaming result set and falls off the end of {@code main}:
     *
     * <pre>
     *   nothing                                          exit 134
     *   chdb_shutdown() with the connection still open   returns 1, exit 134
     *   close the connection, then chdb_shutdown()       returns 0, exit 0
     * </pre>
     *
     * The middle row is the engine's documented contract, not a defect: while a connection is
     * open {@code chdb_shutdown()} does nothing and reports an error, because tearing the
     * engine down under a live connection would leave it dangling. So it does not replace the
     * drain above — the drain is what makes it able to do anything at all.
     *
     * <p><strong>It did not change any exit code this driver can measure.</strong> Every shape
     * tried on macOS arm64, drain-only against drain-then-{@code chdb_shutdown()}, six runs
     * each, came out identical: the leaked-stream exit was already clean from the drain alone,
     * and the case the drain declines to touch — a thread with a query still in flight when
     * the process halts — exits cleanly with this call returning {@code CHDBError}, since an
     * open connection is exactly what it refuses to act under. See "Connections it declines to
     * close" above for that one.
     *
     * <p>It is called anyway, because what it guarantees is an ordering an exit code cannot
     * see: no engine thread is alive when the host proceeds to its own native teardown —
     * global destructors, a sanitizer's exit handler, a finalizing runtime. That is what the
     * engine documents it for, and the case where the race is not the engine aborting on its
     * own state but something else running against threads it does not know about.
     *
     * <p>The cost is that it closes the engine for the rest of the process: {@code
     * chdb_connect()} fails afterwards. Narrow, because it happens after the drain has already
     * gone quiet — a connection arriving that late was unreachable to the hook anyway, and a
     * clean refusal beats the abort it would have caused at exit. But a host that queries chDB
     * from a shutdown hook of its own cannot rely on ordering to avoid it: the JVM runs
     * shutdown hooks concurrently and in no defined order, so its query and this call race.
     * Such a host should do its chDB work before shutdown, or turn this off with {@code
     * -Dchdb.shutdownHook=false} and manage teardown itself.
     */
    private static void stopEngine() {
        try {
            // Absent on engines before v26.7.2-rc.2, which report 2. Nothing to do about
            // either that or a failure: the threads it would have joined are reaped by
            // process exit, exactly as they were before the call existed.
            ChdbNative.shutdown();
        } catch (Throwable ignored) {
            // A shutdown hook has nobody to report to.
        }
    }

    /**
     * Package-private rather than private so a test can drive it.
     *
     * <p>The interesting part of this method is what it does <em>before</em> the JVM halts,
     * and a forked-process test cannot see that: once the last shutdown hook returns the JVM
     * is gone, so "did it wait for the connect that was in progress?" leaves no trace in an
     * exit code. Calling it directly is the only way to assert the wait. Not public and not
     * on any published type — {@code ShutdownCleanup} itself is package-private — so this is
     * a seam for {@code ShutdownCleanupTest}, not surface a caller can reach.
     */
    static void drainOpenConnections() {
        // Drained rather than snapshotted once. A thread that connects and opens a stream
        // after a single snapshot was taken would be missed, leaving exactly the state this
        // hook exists to prevent -- and shutdown hooks run alongside application threads, so
        // that race is ordinary rather than exotic.
        long deadline = System.nanoTime() + DRAIN_BUDGET_NANOS;
        while (true) {
            List<ChdbConnection> batch = new ArrayList<>();
            synchronized (OPEN) {
                for (Iterator<ChdbConnection> it = OPEN.keySet().iterator(); it.hasNext(); ) {
                    ChdbConnection connection = it.next();
                    // Claimed, not inspected. Asking "is a statement executing?" and then
                    // closing is check-then-act, and this thread runs alongside the
                    // application's: a statement starting between the question and the close
                    // would put the connection back in the state that aborts. The claim is a
                    // compare-and-set that succeeds only from idle and, once it succeeds,
                    // stops any further statement from starting -- so it settles the question
                    // it asks. See ExecutionGate.
                    if (!connection.claimForShutdownClose()) {
                        // Left registered rather than taken, which is what gives it another
                        // look on the next pass: a query that ends inside the drain's remaining
                        // time gets closed after all, and one that does not is simply left
                        // open. Not closing it is the point -- see the class javadoc for the
                        // measurement -- and leaving it here costs nothing, because a pass that
                        // takes nothing is a pass that sleeps, so this cannot spin.
                        continue;
                    }
                    batch.add(connection);
                    it.remove();
                }
            }

            // Outside the lock, and that is not a compromise. close() reaches the engine, and
            // chdb_close_conn() blocks until the connection's query finishes -- 79 s in the
            // worst case measured -- so a monitor held across it would stall every thread
            // trying to register, unregister or start a statement for that whole time, which
            // is a worse failure than the one this class is fixing. Nothing is lost by
            // releasing it: the claim above already stopped new statements on every connection
            // in this batch, so the exclusion these closes need is carried by the connection's
            // own gate rather than by any lock this thread holds.
            for (ChdbConnection connection : batch) {
                noteActivity();
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

            if (batch.isEmpty()) {
                // A connect in progress is a handle that exists and has not announced itself,
                // so keep waiting for it however long the registry has been quiet. Bounded by
                // the deadline checked above, which is the whole reason this is a "while the
                // count is non-zero" loop and not a join.
                if (CONNECTS_IN_FLIGHT.get() == 0
                        && (!sawActivity
                                || System.nanoTime() - lastActivityNanos >= QUIET_PERIOD_NANOS)) {
                    return;
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
