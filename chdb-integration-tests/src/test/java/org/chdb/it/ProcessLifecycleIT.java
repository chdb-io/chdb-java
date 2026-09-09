package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        String output;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(3, TimeUnit.MINUTES), "the forked JVM did not exit");
        return new ForkResult(process.exitValue(), output);
    }

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
        // Asserting only that the switch is honoured, not that the engine aborts. The abort is
        // the current behaviour of engine 26.7.0 and pinning a test to it would turn an
        // upstream fix -- or chdb_shutdown() arriving -- into a CI failure. What matters here
        // is that a host managing its own teardown can turn the hook off.
        ForkResult result =
                fork(ExitsWithOpenStream.class.getName(), List.of("-Dchdb.shutdownHook=false"));
        assertTrue(result.output.contains("leaked an open stream"), result.output);
        if (result.exitCode != 0) {
            System.out.println(
                    "with the hook off, this engine exits " + result.exitCode
                            + " -- which is what the hook exists to prevent");
        }
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
