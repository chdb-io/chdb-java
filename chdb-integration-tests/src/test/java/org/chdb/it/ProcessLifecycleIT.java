package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * How the driver behaves at the edges of a process: exiting, and a thread waiting on a
 * connection another thread is using.
 *
 * <p>The exit tests fork a JVM, because the thing being asserted is the exit status of a
 * process, which cannot be observed from inside one.
 */
class ProcessLifecycleIT extends NativeTestBase {

    /** Runs a class in a fresh JVM with the same classpath and library path. */
    private static ForkResult fork(String mainClass, List<String> jvmArgs, String... args)
            throws IOException, InterruptedException {
        return fork(mainClass, jvmArgs, Map.of(), args);
    }

    /**
     * The same, with environment variables added to the inherited environment.
     *
     * <p>Used to start a JVM under a chosen locale, which is the only way to reach a
     * non-Unicode {@code sun.jnu.encoding}: it is read from the OS locale at startup and no
     * system property overrides it (JEP 400 changed {@code file.encoding} and deliberately
     * left this one alone).
     */
    private static ForkResult fork(
            String mainClass, List<String> jvmArgs, Map<String, String> environment, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.add("-Dchdb.library.path=" + System.getProperty("chdb.library.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();

        // Drained on its own thread rather than in line, so the timeout below is reachable.
        // Reading to EOF on this thread cannot be bounded: a child that stalls while holding
        // its stdout open blocks the read, and the waitFor after it never runs. That is not
        // hypothetical -- a stalled child once turned one of these tests into an 87-minute
        // one, where a bounded harness would have failed it in three minutes with the child's
        // output in hand. ByteArrayOutputStream is internally synchronized, so reading it here
        // after the join is safe even when the join times out.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread drain =
                new Thread(
                        () -> {
                            try (InputStream in = process.getInputStream()) {
                                byte[] chunk = new byte[8192];
                                int read;
                                while ((read = in.read(chunk)) > 0) {
                                    buffer.write(chunk, 0, read);
                                }
                            } catch (IOException ignored) {
                                // The process died mid-read, which waitFor below reports.
                            }
                        },
                        "chdb-it-fork-drain");
        drain.setDaemon(true);
        drain.start();

        boolean exited = process.waitFor(FORK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!exited) {
            // Killed rather than left behind: a surviving child holds the engine's storage
            // path and would break every test after this one.
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        }
        drain.join(TimeUnit.SECONDS.toMillis(30));
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(
                exited,
                "the forked JVM did not exit within " + FORK_TIMEOUT_MINUTES + " minutes and was"
                        + " killed. Its output so far:\n" + output);
        return new ForkResult(process.exitValue(), output);
    }

    /**
     * How long any forked JVM here gets. Every one of them either exits in about a second or is
     * wedged; the margin is for a cold CI runner loading a 342 MB engine, not for slow work.
     */
    private static final int FORK_TIMEOUT_MINUTES = 3;

    private static final class ForkResult {
        final int exitCode;
        final String output;

        ForkResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * A JVM that exits with a streaming result set still open.
     *
     * <p>Without the shutdown hook this aborts inside the engine rather than exiting:
     *
     * <pre>
     * libc++ Hardening assertion !empty() failed: front() called on an empty vector
     * </pre>
     *
     * with SIGABRT (exit 134) and a stack naming ClickHouse internals. Narrow but easy to hit:
     * exiting with a Connection open is fine, and closing the Connection while a stream is open
     * is fine, because that closes the stream. Only an open stream at exit does it.
     *
     * <p>Still true on engine 26.7.2-rc.2, which is the baseline that added {@code
     * chdb_shutdown()}: that call declines to do anything while a connection is open, so it
     * does not replace the hook's drain. See {@link #chdbShutdownStopsTheEngine()}.
     */
    public static final class ExitsWithOpenStream {
        public static void main(String[] args) throws Exception {
            Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT number FROM numbers(100000000)");
            rs.next();
            System.out.println("leaked an open stream, now exiting");
            // Deliberately no close, and deliberately falling off the end of main rather than
            // calling System.exit: both routes run shutdown hooks.
        }
    }

    @Test
    @DisplayName("a JVM that exits with a streaming result set open still exits cleanly")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void exitWithOpenStreamIsClean() throws Exception {
        ForkResult result = fork(ExitsWithOpenStream.class.getName(), List.of());
        assertTrue(result.output.contains("leaked an open stream"), result.output);
        assertEquals(
                0,
                result.exitCode,
                "expected a clean exit; 134 means the engine aborted on the way out and the"
                        + " shutdown hook did not close the stream.\n"
                        + result.output);
    }

    @Test
    @DisplayName("the same JVM with the hook disabled reaches the engine's own teardown path")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void hookCanBeDisabled() throws Exception {
        // Still asserting only that the switch is honoured, not that the engine aborts.
        //
        // chdb_shutdown() arriving in the 26.7.2-rc.2 baseline was the event this test was
        // written to survive, and surviving it is exactly what happened: the abort is
        // unchanged, because chdb_shutdown() refuses to act while a connection is open and
        // this JVM's connection is open. So there is still no correct exit code to pin here,
        // and pinning 134 would turn a real upstream fix into a CI failure. What matters is
        // that a host managing its own teardown can turn the hook off.
        //
        // Issue #22 did not change that either. It removed the hook's other hazard -- see
        // exitWithQueriesInFlightIsPrompt -- but this shape's abort is the engine's own
        // behaviour on an exit with a stream open, which no driver change reaches. The switch
        // is therefore still the escape hatch and still only assertable as a switch.
        ForkResult result =
                fork(ExitsWithOpenStream.class.getName(), List.of("-Dchdb.shutdownHook=false"));
        assertTrue(result.output.contains("leaked an open stream"), result.output);
        if (result.exitCode != 0) {
            System.out.println(
                    "with the hook off, this engine exits " + result.exitCode
                            + " -- which is what the hook exists to prevent");
        }
    }

    /**
     * A JVM that falls off the end of {@code main} with background threads mid-query.
     *
     * <p>The ordinary shape of a service taking SIGTERM with requests still running. The
     * threads are daemons, so nothing waits for them; the queries are long enough that they
     * are certainly still inside the engine when the hook runs.
     */
    public static final class ExitsWithQueriesInFlight {
        public static void main(String[] args) throws Exception {
            int threads = 2;
            CountDownLatch started = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                Thread worker =
                        new Thread(
                                () -> {
                                    try (Connection connection =
                                                    DriverManager.getConnection("jdbc:chdb::memory:");
                                            Statement statement = connection.createStatement()) {
                                        started.countDown();
                                        try (ResultSet rs =
                                                statement.executeQuery(
                                                        "SELECT count() FROM numbers(2000000000)"
                                                                + " WHERE sipHash64(number) %"
                                                                + " 1000000 = 0")) {
                                            rs.next();
                                        }
                                        System.out.println("a query finished before the exit");
                                    } catch (Throwable e) {
                                        System.out.println("worker raised " + e);
                                    }
                                },
                                "chdb-it-query-" + i);
                worker.setDaemon(true);
                worker.start();
            }
            assertTrue(started.await(60, TimeUnit.SECONDS), "the workers never started querying");
            // Long enough for both to be inside the engine rather than just past the latch.
            Thread.sleep(1000);
            System.out.println(EXIT_MARKER + System.currentTimeMillis());
        }
    }

    @Test
    @DisplayName("exiting with queries in flight does not stall on the hook")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void exitWithQueriesInFlightIsPrompt() throws Exception {
        // Issue #22 defect 1. The hook used to close every registered connection, including
        // ones whose query was executing, and that did two things. It aborted the process
        // with the engine assertion this whole class exists to prevent -- 21 runs in 60 on
        // macOS arm64, engine 26.7.2-rc.2, against 0 in 60 with the hook off. And, when it
        // did not, it held the JVM open for the length of the query, because
        // chdb_close_conn() on a connection with a query running blocks until the query
        // finishes: 34.5 / 33.9 / 33.9 s of shutdown for a five-billion-row aggregate,
        // against 1.28 / 1.09 / 1.10 s of total process time now. The drain's 5 s budget
        // cannot bound that, because the budget is checked between passes and a close already
        // under way is not interruptible.
        ForkResult result = fork(ExitsWithQueriesInFlight.class.getName(), List.of());
        boolean aQueryFinished = result.output.contains("a query finished before the exit");

        // The abort the hook was causing, named rather than inferred from the exit code.
        // Asserted this way round because there is a second, rarer abort in this shape that
        // the driver does not reach: C++ exit-time destructors running while a thread is
        // still inside the engine, which surfaces as "mutex lock failed: Invalid argument"
        // and appeared at the same rate with the hook on (2 of 80) and off (3 of 80), in
        // bursts that track machine load. Pinning exit 0 here would make this test fail for
        // that instead, and it is not something a shutdown hook can fix -- turning the hook
        // off does not avoid it either. See upstream findings §9.
        assertTrue(
                !result.output.contains("front() called on an empty vector"),
                "the hook must not close a connection whose statement is executing; that is"
                        + " what provokes this assertion.\n"
                        + result.output);
        if (result.exitCode != 0) {
            System.out.println(
                    "exiting with queries in flight came out " + result.exitCode
                            + " -- the engine's own exit-time race, not the hook's doing;"
                            + " see upstream findings section 9");
        }

        long shutdownMillis = shutdownMillis(result);
        System.out.println("shutdown with queries in flight took " + shutdownMillis + " ms");

        // The query outlives this bound by a wide margin on any machine that can run it at
        // all: two threads on two billion rows takes ~13 s here, and the shutdown takes
        // ~320 ms on JDK 26 and ~900 ms on JDK 21. So a shutdown that waited for the query
        // cannot come in under 8 s.
        //
        // The message distinguishes the two ways this can be slow, because they need
        // different responses and the child's own output separates them. If the hook waited
        // on chdb_close_conn(), that call returns only once the query has finished -- so the
        // worker will have printed that it finished, and the defect is back. If nothing
        // finished, the hook did not wait for any query and the process stalled for some other
        // reason; that has been seen once, at 87 minutes, on an otherwise-loaded machine, and
        // never reproduced in 8 further runs on the same JDK.
        if (shutdownMillis >= 8000) {
            throw new AssertionError(
                    "shutdown took "
                            + shutdownMillis
                            + " ms with queries in flight.\n"
                            + (aQueryFinished
                                    ? "A query finished, which is what chdb_close_conn()"
                                            + " returning looks like: the hook closed a"
                                            + " connection whose statement was executing and"
                                            + " blocked on it. That is the defect."
                                    : "No query finished, so the hook waited for none of them"
                                            + " -- this is a stall elsewhere in the process,"
                                            + " not the hook closing a busy connection.")
                            + "\n"
                            + result.output);
        }

        // Only meaningful once the shutdown was fast: a query that finished on its own leaves
        // an idle connection, which the hook closes as it always did, so the run proved nothing
        // about the state this test exists for.
        assertTrue(
                !aQueryFinished,
                "the queries were meant to still be running at exit; make them longer.\n"
                        + result.output);
    }

    /**
     * A connect that fails inside {@code chdb_connect()}, then an ordinary session.
     *
     * <p>The failing URL names a storage path whose parent is a regular file, which no
     * platform can turn into a directory -- so the driver gets past its own URL parsing and
     * the storage-path registry and fails in the engine, which is the only failure that lands
     * between the in-flight-connect count going up and coming back down.
     *
     * <p>The second, successful connection is what installs the shutdown hook: the first one
     * never reached {@code register()}.
     */
    public static final class ConnectsBadlyThenCleanly {
        public static void main(String[] args) throws Exception {
            Path parent = Files.createTempFile("chdb-not-a-directory", ".tmp");
            try (Connection connection =
                    DriverManager.getConnection("jdbc:chdb:" + parent + File.separator + "db")) {
                System.out.println("unexpected: the engine accepted a path under a file");
            } catch (SQLException expected) {
                System.out.println("bad path refused");
            }

            try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                rs.next();
            }
            System.out.println(EXIT_MARKER + System.currentTimeMillis());
        }
    }

    @Test
    @DisplayName("a connect that failed does not make later exits pay the drain budget")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aFailedConnectDoesNotStallTheExit() throws Exception {
        // The way the in-flight-connect count is most easily got wrong. It is raised before
        // chdb_connect() and lowered after register(); if the lowering is not in a finally, a
        // single failed connect leaves it raised forever and the drain then waits out its
        // whole 5 s budget on every subsequent exit -- silently, since the exit code is still
        // 0. So the assertion has to be about time, not status.
        ForkResult result = fork(ConnectsBadlyThenCleanly.class.getName(), List.of());
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("bad path refused"), result.output);

        long shutdownMillis = shutdownMillis(result);
        // Measured on macOS arm64: 13-14 ms over three runs, against 400 ms for a leaked
        // stream and 5000 ms for a leaked count. Anywhere under 3 s means the count came back
        // down; the gap is wide enough that a slow CI machine cannot close it.
        assertTrue(
                shutdownMillis < 3000,
                "the hook should have had nothing to wait for after a failed connect, but"
                        + " shutdown took "
                        + shutdownMillis
                        + " ms -- an in-flight-connect count left raised by the failure would"
                        + " spend the drain's whole budget.\n"
                        + result.output);
    }

    /**
     * Printed by a forked program as the last thing {@code main} does, carrying the wall clock
     * at that moment.
     *
     * <p>What these tests need to bound is the shutdown phase, and total process time cannot
     * do it: loading a 342 MB engine dominates and varies by an order of magnitude between a
     * warm laptop and a cold CI runner. Subtracting the marker from the moment the process
     * exits leaves the hook's own contribution.
     */
    private static final String EXIT_MARKER = "exit-at=";

    private static long shutdownMillis(ForkResult result) {
        long now = System.currentTimeMillis();
        for (String line : result.output.split("\n")) {
            if (line.startsWith(EXIT_MARKER)) {
                return now - Long.parseLong(line.substring(EXIT_MARKER.length()).trim());
            }
        }
        throw new AssertionError("the forked program never reached the end of main:\n" + result.output);
    }

    /**
     * Calls {@code chdb_shutdown()} the way the hook does, and reports what it answered.
     *
     * <p>In a forked JVM because it is irreversible: the engine is closed for the rest of the
     * process once it returns, so a reconnect afterwards has to fail.
     */
    public static final class ShutsTheEngineDown {
        public static void main(String[] args) throws Exception {
            try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)")) {
                rs.next();
            }
            System.out.println("shutdown=" + ChdbNative.shutdown());
            System.out.println("again=" + ChdbNative.shutdown());
            try {
                DriverManager.getConnection("jdbc:chdb::memory:").close();
                System.out.println("reconnect=allowed");
            } catch (SQLException e) {
                System.out.println("reconnect=refused");
            }
        }
    }

    @Test
    @DisplayName("chdb_shutdown() is on the baseline, succeeds once everything is closed, and"
            + " closes the engine")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void chdbShutdownStopsTheEngine() throws Exception {
        // The premise of what ShutdownCleanup does at the end of its drain. Worth a test of its
        // own because all three parts are assumptions about the pinned engine rather than about
        // this driver: that the symbol is there at all (2 means it is not), that it succeeds
        // once the connections are closed, and that it is idempotent.
        ForkResult result = fork(ShutsTheEngineDown.class.getName(), List.of());
        assertEquals(0, result.exitCode, result.output);
        assertTrue(
                result.output.contains("shutdown=0"),
                "chdb_shutdown() should stop the engine once every connection is closed;"
                        + " 2 means this engine does not export it at all, which would remove the"
                        + " reason the baseline moved to v26.7.2-rc.2.\n"
                        + result.output);
        assertTrue(
                result.output.contains("again=0"),
                "the engine documents a second call as harmless:\n" + result.output);
        // Not asserted as refused: what matters is that the process still exits 0 either way.
        // The engine says connecting afterwards fails, and it does here, but a future engine
        // being able to restart is not a regression this test should manufacture.
        System.out.println("after chdb_shutdown(), " + (result.output.contains("reconnect=refused")
                ? "reconnecting is refused" : "reconnecting is allowed"));
    }

    /** A JVM that closes everything before exiting, which must also be clean. */
    public static final class ExitsCleanly {
        public static void main(String[] args) throws Exception {
            try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)")) {
                while (rs.next()) {
                    rs.getLong(1);
                }
            }
            System.out.println("closed everything");
        }
    }

    @Test
    @DisplayName("closing everything exits cleanly, hook or no hook")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void cleanShutdownIsClean() throws Exception {
        assertEquals(0, fork(ExitsCleanly.class.getName(), List.of()).exitCode);
        assertEquals(
                0, fork(ExitsCleanly.class.getName(), List.of("-Dchdb.shutdownHook=false")).exitCode);
    }

    /** A JVM whose storage path contains characters that trip naive path handling. */
    public static final class QueriesAwkwardPath {
        public static void main(String[] args) throws Exception {
            // The path arrives base64-encoded. ProcessBuilder encodes program arguments with
            // sun.jnu.encoding, which is ASCII on a JVM started without a locale, so a Unicode
            // name passed directly would arrive as question marks and this would silently test
            // the wrong path.
            String path =
                    new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8);
            System.out.println("sun.jnu.encoding=" + System.getProperty("sun.jnu.encoding"));

            try (Connection connection = DriverManager.getConnection("jdbc:chdb:" + path);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (x UInt32) ENGINE = MergeTree ORDER BY x");
                statement.executeUpdate("INSERT INTO t VALUES (7)");
                try (ResultSet rs = statement.executeQuery("SELECT sum(x) FROM t")) {
                    rs.next();
                    System.out.println("sum=" + rs.getLong(1));
                }
            } catch (SQLException e) {
                // Printed rather than thrown so the parent's failure message carries the
                // driver's own diagnostic. A stack trace through an ASCII stdout, which is what
                // a JVM with no locale gives you, would be unreadable anyway.
                System.out.println(
                        "refused sqlstate=" + e.getSQLState() + " message=" + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("storage paths with spaces, Unicode and a long name work")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void awkwardStoragePaths() throws Exception {
        // A separate JVM per path, because the engine binds one storage path per process.
        // Work plan section 5.12 asks for exactly these three.
        Path base = Files.createTempDirectory("chdb awkward");
        String[] names = {
            "with spaces",
            "unicode-数据库-δεδομένα-🎉",
            // Long, but within the 255-byte per-component limit every filesystem here enforces.
            "long-" + repeat("x", 180),
        };

        for (String name : names) {
            // Concatenated rather than base.resolve(name): resolve() encodes with
            // sun.jnu.encoding and throws InvalidPathException on an ASCII-locale JVM before
            // the driver is reached at all, which would make this a test of java.nio.file.
            String path = base + File.separator + name;
            ForkResult result =
                    fork(
                            QueriesAwkwardPath.class.getName(),
                            List.of(),
                            Base64.getEncoder()
                                    .encodeToString(path.getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, result.exitCode, "path " + name + " -> " + result.output);

            // Unconditionally, including on a JVM whose sun.jnu.encoding cannot hold the name.
            // That case used to be a refusal, on the grounds that Paths.get could not resolve
            // the path the storage-path registry compares by; the driver now resolves it as
            // text instead (issue #7). Nothing else about the path went through java.nio.file:
            // the engine is handed UTF-8 bytes and does its own mkdir.
            assertTrue(result.output.contains("sum=7"), "path " + name + " -> " + result.output);
        }
    }

    @Test
    @DisplayName("a Unicode storage path works with no UTF-8 locale, where java.nio.file cannot hold it")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void unicodeStoragePathWithoutAUtf8Locale() throws Exception {
        // The case issue #7 is about, forced rather than waited for: a JVM started with LANG=C
        // reads its filesystem encoding off the OS locale and gets ASCII, so Paths.get cannot
        // encode this name. -Dfile.encoding does not help -- JEP 400 deliberately left
        // sun.jnu.encoding following the locale.
        //
        // What the locale actually buys varies by platform: measured on macOS 26, the JVM
        // reports sun.jnu.encoding=UTF-8 under LANG=C LC_ALL=C and even under
        // -Dsun.jnu.encoding=US-ASCII, so there this degrades to re-running the case above and
        // the note below says so. On glibc it is ANSI_X3.4-1968 and the fallback is what makes
        // this pass. The resolution itself is asserted directly, and differentially against
        // java.nio.file, in ChdbUrlTest, which needs no particular locale.
        Path base = Files.createTempDirectory("chdb no locale");
        String path = base + File.separator + "unicode-数据库-δεδομένα-🎉";

        ForkResult result =
                fork(
                        QueriesAwkwardPath.class.getName(),
                        List.of(),
                        Map.of("LANG", "C", "LC_ALL", "C"),
                        Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, result.exitCode, result.output);
        assertTrue(
                result.output.contains("sum=7"),
                "a Unicode storage path must work whether or not the JVM's locale can express"
                        + " it -- the engine takes the path as UTF-8 bytes and creates the"
                        + " directory itself:\n"
                        + result.output);
        if (result.output.contains("sun.jnu.encoding=UTF-8")) {
            System.out.println(
                    "this platform reports sun.jnu.encoding=UTF-8 even under LANG=C, so the"
                            + " textual fallback was not the thing under test here");
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            out.append(s);
        }
        return out.toString();
    }

    @Test
    @DisplayName("a connection the hook has claimed refuses new statements with a clear error")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aClaimedConnectionRefusesNewStatements() throws Exception {
        // The application-visible half of issue #22 defect 1's fix. Once the shutdown hook has
        // claimed a connection, nothing may start a statement on it -- closing a connection
        // with a statement starting is what aborts the engine. So a statement that arrives in
        // that window has to be refused, and refused in a way a caller can read: not a
        // NullPointerException from a half-torn-down connection, not a silent no-op, and not
        // the abort.
        //
        // Driven through the same call the drain uses, rather than by forking a JVM and hoping
        // to land in the window. See ShutdownHookAccess for why that is a test-only class.
        try (Connection connection = openMemory()) {
            try (Statement warm = connection.createStatement();
                    ResultSet rs = warm.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "the connection should work before it is claimed");
            }

            assertTrue(
                    org.chdb.jdbc.ShutdownHookAccess.claim(connection),
                    "an idle connection is exactly what the hook is allowed to take");
            assertTrue(org.chdb.jdbc.ShutdownHookAccess.isClaimed(connection));

            try (Statement statement = connection.createStatement()) {
                SQLException refused =
                        assertThrows(
                                SQLException.class, () -> statement.executeQuery("SELECT 1"));
                assertEquals("08003", refused.getSQLState(), refused.getMessage());
                assertTrue(
                        refused.getMessage().contains("shutting down"),
                        "the message must say why, so a caller is not left guessing: "
                                + refused.getMessage());

                assertNull(
                        statement.getResultSet(),
                        "a refused statement must not leave a result set behind");
                assertFalse(statement.isClosed(), "refusing is not closing the Statement");

                // The refusal must also have released the connection's statement slot. If it
                // had not, this second attempt would report SQLSTATE 25000 -- "a statement is
                // already in progress on this thread" -- instead of the refusal, and a
                // connection shared with another thread would have been wedged for good.
                SQLException again =
                        assertThrows(
                                SQLException.class, () -> statement.executeQuery("SELECT 1"));
                assertEquals(
                        "08003",
                        again.getSQLState(),
                        "the slot was not released by the refusal: " + again.getMessage());

                // All three routes, because each reaches the engine through a different native
                // statement-start call -- the streaming open, chdb_query_arrow_n for a
                // materialized result set, and chdb_query_n for one with no result set -- and
                // the abort does not care which. The gate is taken before the route is even
                // decided, which is what makes one refusal cover all three; this is the test
                // that would go red if it were ever moved inside a branch.
                SQLException materialized =
                        assertThrows(
                                SQLException.class, () -> statement.executeQuery("SHOW TABLES"));
                assertEquals(
                        "08003",
                        materialized.getSQLState(),
                        "the materialized route must be refused too: " + materialized.getMessage());

                SQLException noResultSet =
                        assertThrows(
                                SQLException.class,
                                () -> statement.execute("DROP TABLE IF EXISTS chdb_it_absent"));
                assertEquals(
                        "08003",
                        noResultSet.getSQLState(),
                        "the no-result-set route must be refused too: "
                                + noResultSet.getMessage());
            }
        }
        // assertNoLeakedHandles() runs after this and is the check that the refusal left no
        // stream, result or connection handle open.
    }

    @Test
    @DisplayName("the hook cannot claim a connection whose statement is still starting")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void aClaimLosesToAStatementInFlight() throws Exception {
        // The other half, and the one the whole diagnosis turned on: the hook must lose to a
        // thread that is inside the engine starting a statement. ExecutionGateTest pins the
        // state machine; this pins the wiring, that executeInternal really does hold the gate
        // across the native statement-start call rather than around something narrower.
        //
        // An aggregate with no result set until it finishes is what keeps the thread inside
        // that call. It runs ~3.2 s here against the ~250 ms this test spends asserting, and
        // the margin scales the right way -- a slower machine makes the query longer too.
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            AtomicBoolean finished = new AtomicBoolean(false);
            CountDownLatch entered = new CountDownLatch(1);
            AtomicReference<Exception> raised = new AtomicReference<>();

            Thread querying =
                    new Thread(
                            () -> {
                                entered.countDown();
                                try (ResultSet rs =
                                        statement.executeQuery(
                                                "SELECT count() FROM numbers(500000000)"
                                                        + " WHERE sipHash64(number) % 1000000"
                                                        + " = 0")) {
                                    rs.next();
                                } catch (Exception e) {
                                    raised.set(e);
                                } finally {
                                    finished.set(true);
                                }
                            },
                            "chdb-it-inflight");
            querying.setDaemon(true);
            querying.start();

            assertTrue(entered.await(30, TimeUnit.SECONDS), "the querying thread never started");
            // Let it get past the gate and into the engine rather than racing the latch.
            Thread.sleep(150);

            int attempts = 0;
            while (!finished.get() && attempts < 5) {
                assertFalse(
                        org.chdb.jdbc.ShutdownHookAccess.claim(connection),
                        "the hook took a connection with a statement still starting on it;"
                                + " closing it is what aborts the engine");
                attempts++;
                Thread.sleep(20);
            }
            assertTrue(
                    attempts > 0,
                    "the query finished before a single claim was attempted; make it longer");

            querying.join(TimeUnit.MINUTES.toMillis(2));
            assertNull(raised.get(), "the query itself should have been undisturbed");

            // And the moment it is done, the hook may have it -- which is what makes the
            // drain's next pass worth taking rather than a wasted one.
            assertTrue(
                    org.chdb.jdbc.ShutdownHookAccess.claim(connection),
                    "an idle connection must be claimable");
        }
    }

    @Test
    @DisplayName("every way a statement can end puts the shutdown gate back")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void theGateComesBackOnEveryPath() throws Exception {
        // A gate left raised is silent in both directions: the shutdown hook skips this
        // connection for the rest of the process, so it stops doing its job, and nothing is
        // refused either, so nothing reports it. The exits are in the same finally as the
        // statement slot's release, and PR #16 rearranged that method's try structure around
        // the query-timeout check -- so each way out of it is walked here.
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            assertEquals(0, org.chdb.jdbc.ShutdownHookAccess.inFlight(connection));

            // Streaming route, ordinary success.
            try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(10)")) {
                assertTrue(rs.next());
            }
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a successful streaming statement");

            // Materialized route, ordinary success.
            try (ResultSet rs = statement.executeQuery("SHOW TABLES")) {
                rs.next();
            }
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a successful materialized statement");

            // No-result-set route.
            statement.execute("DROP TABLE IF EXISTS chdb_it_gate_absent");
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a successful statement with no result set");

            // The engine refusing the statement, which throws out of the open.
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT no_such_thing"));
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a statement the engine rejected");

            // The driver refusing it before the engine: executeQuery on a statement with no
            // result set returns through the expectResultSet early exit.
            assertThrows(
                    SQLException.class,
                    () -> statement.executeQuery("DROP TABLE IF EXISTS chdb_it_gate_absent"));
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a statement refused for the wrong shape");

            // The path PR #16 added: a deadline that expired while the open was in flight, so
            // checkDeadlineSurvivedTheOpen closes the result set and throws 57014 from a point
            // where handedOff is already set. Either outcome is acceptable -- a fast enough
            // machine beats the deadline -- but the gate has to be back regardless.
            statement.setQueryTimeout(1);
            try (ResultSet rs =
                    statement.executeQuery(
                            "SELECT max(sipHash64(number)) FROM numbers(500000000)")) {
                assertTrue(rs.next());
                System.out.println("the 1 s deadline was met, so 57014 was not the path taken");
            } catch (SQLTimeoutException expected) {
                assertEquals("57014", expected.getSQLState());
            }
            statement.setQueryTimeout(0);
            assertEquals(
                    0,
                    org.chdb.jdbc.ShutdownHookAccess.inFlight(connection),
                    "a statement whose deadline expired during the open");

            // And the hook can still have the connection, which is the whole point of keeping
            // the count honest.
            assertTrue(
                    org.chdb.jdbc.ShutdownHookAccess.claim(connection),
                    "after all of that the gate must still be free");
        }
    }

    @Test
    @DisplayName("a thread waiting for a busy connection can be interrupted out of the wait")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void waitingForABusyConnectionIsInterruptible() throws Exception {
        // A Connection shared across threads with a result set left open on one of them blocks
        // the other indefinitely -- the statement slot is held until that result set closes.
        // That is the designed behaviour: a legitimate long query must not be cut short.
        //
        // What matters is that the waiting thread is not stuck beyond recovery. It can be
        // interrupted, and it says what happened rather than dying silently.
        //
        // A connection pool never reaches this: each pooled Connection belongs to one thread at
        // a time, and HikariCP closes statements when a connection is returned.
        try (Connection connection = openMemory()) {
            Statement holder = connection.createStatement();
            ResultSet held = holder.executeQuery("SELECT number FROM numbers(100000000)");
            assertTrue(held.next());

            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<Exception> raised = new AtomicReference<>();

            Thread other =
                    new Thread(
                            () -> {
                                waiting.countDown();
                                try (Statement statement = connection.createStatement();
                                        ResultSet rs = statement.executeQuery("SELECT 1")) {
                                    rs.next();
                                } catch (Exception e) {
                                    raised.set(e);
                                }
                            },
                            "chdb-it-waiter");
            other.start();

            assertTrue(waiting.await(30, TimeUnit.SECONDS));
            // Give it a moment to actually reach the wait rather than racing the latch.
            Thread.sleep(500);
            assertTrue(other.isAlive(), "the second thread should be waiting for the slot");

            other.interrupt();
            other.join(TimeUnit.SECONDS.toMillis(30));
            assertTrue(!other.isAlive(), "an interrupt must get the thread out of the wait");

            Exception e = raised.get();
            assertNotNull(e, "the interrupted thread should report why it gave up");
            assertTrue(e instanceof SQLException, "expected SQLException, got " + e);
            assertTrue(
                    e.getMessage().contains("Interrupted"),
                    "the message should say it was interrupted: " + e.getMessage());

            held.close();
            holder.close();
        }
    }

    @Test
    @DisplayName("the same thread reusing a busy connection is refused, not deadlocked")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void sameThreadReuseIsRefused() throws Exception {
        try (Connection connection = openMemory();
                Statement holder = connection.createStatement();
                ResultSet held = holder.executeQuery("SELECT number FROM numbers(1000000)")) {
            assertTrue(held.next());

            SQLException e =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                try (Statement statement = connection.createStatement()) {
                                    statement.executeQuery("SELECT 1");
                                }
                            });
            assertEquals("25000", e.getSQLState());
            assertTrue(e.getMessage().contains("still open"), e.getMessage());
        }
    }
}
