package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    @DisplayName("a concurrent thread never sees chDB's own handlers installed during connects")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void concurrentConnectsNeverExposeAChdbHandler() throws Exception {
        // Issue #14. Every other test in this class looks at the dispositions *after* a chDB
        // call, from the thread that made it, and so can only see what the guard restored.
        // What killed the failsafe JVM is what a *different* thread sees *during* a connect,
        // and the two answers are not the same.
        //
        // Two things can be true in that window, and they are not equally bad:
        //
        //   SIG_DFL          -- chDB reset the host handlers and the shim has not restored
        //                       them yet. A few microseconds per connect. Measured on macOS
        //                       arm64 / Java 11: 234 samples out of 478,273 across 200
        //                       connects, ~0.05% of wall clock.
        //   a chDB handler   -- chDB installed its own. This is what happens if the opt-out
        //                       is not made at all, and it is far worse: the engine's handler
        //                       is in place for roughly 60% of every connect (331,296 samples
        //                       out of 557,194 when measured with the opt-out removed), and it
        //                       treats a SIGSEGV HotSpot would have recovered from as a fatal
        //                       crash.
        //
        // So the assertion is the one that is actually ours to keep: the engine's handler must
        // never be installed. The SIG_DFL window is upstream's to close -- it happens inside
        // chdb_connect(), before the shim gets control back -- so it is measured and printed
        // rather than asserted, and reducing the number of windows is what the fix did.
        try (Connection warmup = openMemory()) {
            warmup.isValid(1);
        }

        final String steady = ChdbNative.signalDispositions();
        final Map<String, String> steadyBySignal = parseDispositions(steady);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong samples = new AtomicLong();
        final AtomicLong sawDefault = new AtomicLong();
        final Set<String> sawForeign = ConcurrentHashMap.newKeySet();

        Thread observer =
                new Thread(
                        () -> {
                            while (!stop.get()) {
                                samples.incrementAndGet();
                                Map<String, String> now =
                                        parseDispositions(ChdbNative.signalDispositions());
                                for (Map.Entry<String, String> entry : now.entrySet()) {
                                    // Compared by handler, not by the whole line. sigaction()
                                    // is not an atomic read against a concurrent write, so an
                                    // observer occasionally catches the right handler with the
                                    // flags not yet updated -- seen once in ~400,000 samples,
                                    // as "handler:<host address> flags=0x0". Which handler will
                                    // run is what decides whether the JVM survives; the flags
                                    // are not worth a flaky test.
                                    String expected = handlerOf(steadyBySignal.get(entry.getKey()));
                                    String actual = handlerOf(entry.getValue());
                                    if (actual.equals(expected)) {
                                        continue;
                                    }
                                    if (actual.equals("SIG_DFL") || actual.equals("SIG_IGN")) {
                                        sawDefault.incrementAndGet();
                                    } else {
                                        // Not the host's handler and not the default, so it is
                                        // the engine's. Recorded rather than asserted here,
                                        // because an assertion failure on this thread would be
                                        // swallowed.
                                        sawForeign.add(
                                                entry.getKey()
                                                        + ": steady="
                                                        + steadyBySignal.get(entry.getKey())
                                                        + " observed="
                                                        + entry.getValue());
                                    }
                                }
                            }
                        },
                        "signal-disposition-observer");
        observer.setDaemon(true);
        observer.start();

        final int connects = 200;
        for (int i = 0; i < connects; i++) {
            try (Connection connection = openMemory()) {
                // Nothing to do: opening and closing is the operation under test.
            }
        }
        stop.set(true);
        observer.join(TimeUnit.SECONDS.toMillis(30));

        System.out.println(
                "signal window over "
                        + connects
                        + " connects: "
                        + samples.get()
                        + " samples, "
                        + sawDefault.get()
                        + " observations of a host handler reset to SIG_DFL"
                        + " (upstream's window, see issue #14)");

        assertTrue(
                sawForeign.isEmpty(),
                "another thread saw chDB's own signal handler installed during a connect. The"
                        + " engine's handler treats a SIGSEGV that HotSpot would have recovered"
                        + " from as a fatal crash, so this kills the JVM. It means the opt-out in"
                        + " NativeLibraryLoader.load() is no longer taking effect.\n"
                        + String.join("\n", sawForeign));

        // Only a sanity ceiling on the residual upstream window: it is a few microseconds per
        // connect, so anything approaching the whole run means the guard has stopped restoring
        // rather than that upstream got slower.
        assertTrue(
                sawDefault.get() < samples.get() / 10,
                "the host handlers were at SIG_DFL for "
                        + sawDefault.get()
                        + " of "
                        + samples.get()
                        + " observations, which is far more than the microseconds-per-connect"
                        + " window chdb_connect() accounts for. The shim's SignalGuard is"
                        + " probably not restoring them.");
    }

    /**
     * The handler part of one disposition line, dropping the flags: {@code "SIG_DFL"} or
     * {@code "handler:0x1088ff9bc"}.
     */
    private static String handlerOf(String disposition) {
        if (disposition == null) {
            return "";
        }
        int space = disposition.indexOf(' ');
        return space < 0 ? disposition : disposition.substring(0, space);
    }

    /** Splits {@link ChdbNative#signalDispositions()} into signal name -> disposition. */
    private static Map<String, String> parseDispositions(String report) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : report.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                out.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("the disposition report covers every signal chDB touches")
    void reportCoversUpstreamsSignalList() {
        String report = ChdbNative.signalDispositions();
        // chdb_reset_signal_handlers()'s own list, as of engine 26.7.3.
        for (String signal :
                new String[] {
                    "SIGABRT", "SIGSEGV", "SIGILL", "SIGBUS", "SIGSYS", "SIGFPE", "SIGTSTP", "SIGTRAP"
                }) {
            assertTrue(report.contains(signal + "="), "report omits " + signal + ":\n" + report);
        }
    }
}
