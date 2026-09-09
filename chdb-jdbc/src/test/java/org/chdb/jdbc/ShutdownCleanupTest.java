package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The drain's waiting rules, driven directly.
 *
 * <p>No engine here, and none needed: with an empty registry the drain closes nothing and
 * makes no native call, so what is under test is purely how long it decides to keep looking.
 * That decision is invisible to the forked-process tests in {@code ProcessLifecycleIT} --
 * once the last shutdown hook returns the JVM halts, so "the hook waited for the connect that
 * was in progress" leaves no mark on an exit code. Hence
 * {@link ShutdownCleanup#drainOpenConnections()} being reachable from this package.
 *
 * <p>These tests leave the in-flight count balanced, which is what lets them share the class's
 * static state with each other.
 */
class ShutdownCleanupTest {

    /**
     * Generous, because the assertion is a lower bound on a wait -- a slow machine makes the
     * drain wait longer, never shorter.
     */
    private static final long CONNECT_MILLIS = 600;

    @Test
    @DisplayName("the drain returns at once when nothing is open and nothing is connecting")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anEmptyRegistryCostsNothing() {
        long start = System.nanoTime();
        ShutdownCleanup.drainOpenConnections();
        long elapsed = millisSince(start);

        // The property QUIET_PERIOD_NANOS's comment promises: a clean exit pays nothing for
        // the hook. Asserted here as well as end-to-end in ProcessLifecycleIT, because this
        // is the invariant the in-flight-connect fix was shaped to keep -- the obvious
        // alternative, treating the start of the drain as activity, would make this 400 ms.
        assertTrue(elapsed < 200, "an empty drain should be immediate, took " + elapsed + " ms");
    }

    @Test
    @DisplayName("the drain waits for a connect that has not registered yet")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aConnectInFlightHoldsTheDrain() throws Exception {
        // The case issue #22 is about: ChdbConnection's constructor opens the native handle
        // before it registers, so a hook running in that window sees an empty registry. It
        // used to return immediately, leaving the handle -- and whatever stream that thread
        // went on to open -- to the exit-time abort.
        ShutdownCleanup.connectStarted();
        Thread connecting =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(CONNECT_MILLIS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                ShutdownCleanup.connectFinished();
                            }
                        },
                        "test-connecting");
        connecting.setDaemon(true);

        long start = System.nanoTime();
        connecting.start();
        ShutdownCleanup.drainOpenConnections();
        long elapsed = millisSince(start);
        connecting.join(TimeUnit.SECONDS.toMillis(30));

        assertTrue(
                elapsed >= CONNECT_MILLIS,
                "the drain must not return while a connect is in flight; it returned after "
                        + elapsed
                        + " ms of a "
                        + CONNECT_MILLIS
                        + " ms connect");
    }

    @Test
    @DisplayName("a connect that never finishes only costs the drain budget")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aStuckConnectDoesNotHangTheJvm() {
        // The failure mode the counter introduces if it is written carelessly: waiting on a
        // count that never drops. A shutdown hook that hangs is worse than one that gives up,
        // so the wait is bounded by DRAIN_BUDGET_NANOS (5 s) like every other reason to keep
        // looking. Restored afterwards so the other tests here still start from zero.
        ShutdownCleanup.connectStarted();
        try {
            long start = System.nanoTime();
            ShutdownCleanup.drainOpenConnections();
            long elapsed = millisSince(start);

            assertTrue(
                    elapsed >= 4000,
                    "it should have spent its budget waiting, not " + elapsed + " ms");
            assertTrue(
                    elapsed < 30000,
                    "the drain must give up rather than hang; it took " + elapsed + " ms");
        } finally {
            ShutdownCleanup.connectFinished();
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }
}
