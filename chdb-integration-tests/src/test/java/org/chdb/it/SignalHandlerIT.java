package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Work plan section 5.6 is a release gate: the host JVM's signal dispositions must be identical
 * before and after loading, connecting, querying and closing.
 *
 * <p>This is not a nicety. HotSpot installs handlers for SIGSEGV, SIGBUS, SIGILL and SIGFPE and
 * depends on them for implicit null checks, stack banging and safepoint polling. {@code
 * chdb_set_signal_handlers_enabled(0)} resets exactly those to {@code SIG_DFL} as a side effect
 * of opting out of chDB's handlers, so without the shim's guard the JVM would die on its first
 * ordinary null dereference -- a crash with no connection to chDB in the stack trace.
 */
class SignalHandlerIT extends NativeTestBase {

    @Test
    @DisplayName("chDB's opt-out clobbers the JVM's crash handlers, and the shim restores them")
    void optOutRestoresHostHandlers() {
        String before = ChdbNative.signalDispositions();
        String[] restored = ChdbNative.protectHostSignalHandlers();
        String after = ChdbNative.signalDispositions();

        assertNotNull(restored);
        assertEquals(
                before,
                after,
                "chdb_set_signal_handlers_enabled(0) changed a host signal disposition that the"
                        + " shim did not put back.\nbefore:\n"
                        + before
                        + "after:\n"
                        + after);

        // Not asserted as a fixed set: it is whatever the incumbent handlers were, and it will
        // legitimately become empty once chdb-core grows an opt-out that leaves them alone.
        // Printed because it is the evidence for the gate.
        System.out.println("signals chDB reset and the shim restored: " + Arrays.toString(restored));
    }

    @Test
    @DisplayName("dispositions survive connect, query and close")
    void dispositionsSurviveTheFullLifecycle() throws SQLException {
        // Establish the post-load baseline first: the very first connect in the JVM is what
        // triggers chDB's reset, and comparing across it is the point.
        try (Connection warmup = openMemory()) {
            warmup.isValid(1);
        }

        String baseline = ChdbNative.signalDispositions();

        try (Connection connection = openMemory()) {
            assertEquals(baseline, ChdbNative.signalDispositions(), "connect changed a disposition");

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT number FROM numbers(10000)")) {
                while (rs.next()) {
                    rs.getLong(1);
                }
            }
            assertEquals(baseline, ChdbNative.signalDispositions(), "a query changed a disposition");
        }
        assertEquals(baseline, ChdbNative.signalDispositions(), "close changed a disposition");
    }

    @Test
    @DisplayName("the JVM still handles its own SIGSEGV: a null dereference is an NPE, not a crash")
    void jvmStillOwnsSegv() throws SQLException {
        try (Connection connection = openMemory()) {
            connection.isValid(1);
        }

        // The end-to-end proof. HotSpot implements this NullPointerException by taking a
        // SIGSEGV and recovering in its own handler. If chDB's reset had stuck, this line
        // would terminate the JVM instead of throwing.
        Object nothing = null;
        try {
            nothing.toString();
            org.junit.jupiter.api.Assertions.fail("expected a NullPointerException");
        } catch (NullPointerException expected) {
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("every call re-clobbers the host handlers, and every call restores them")
    void guardIsNeededOnEveryCall() {
        // chdb_set_signal_handlers_enabled(0) resets the incumbent handlers unconditionally --
        // the already-set opt-out flag does not short-circuit the reset. So the guard is
        // load-bearing on every call, not only the first, and calling this twice is safe only
        // because it restores twice.
        String before = ChdbNative.signalDispositions();
        String[] first = ChdbNative.protectHostSignalHandlers();
        assertEquals(before, ChdbNative.signalDispositions(), "first call left a disposition changed");

        String[] second = ChdbNative.protectHostSignalHandlers();
        assertEquals(before, ChdbNative.signalDispositions(), "second call left a disposition changed");

        // Both calls have something to put back, which is the observation worth recording:
        // if this ever becomes empty, upstream has stopped resetting host handlers and the
        // guard can be reconsidered.
        assertEquals(
                Arrays.toString(first),
                Arrays.toString(second),
                "the two calls clobbered different signals, which means the reset is not"
                        + " deterministic and the guard's signal set needs revisiting");
    }

    @Test
    @DisplayName("the disposition report covers every signal chDB touches")
    void reportCoversUpstreamsSignalList() {
        String report = ChdbNative.signalDispositions();
        // chdb_reset_signal_handlers()'s own list, as of engine 26.7.2-rc.2.
        for (String signal :
                new String[] {
                    "SIGABRT", "SIGSEGV", "SIGILL", "SIGBUS", "SIGSYS", "SIGFPE", "SIGTSTP", "SIGTRAP"
                }) {
            assertTrue(report.contains(signal + "="), "report omits " + signal + ":\n" + report);
        }
    }
}
