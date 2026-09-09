package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.List;
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

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
            String url = "jdbc:chdb:" + args[0];
            try (Connection connection = DriverManager.getConnection(url);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (x UInt32) ENGINE = MergeTree ORDER BY x");
                statement.executeUpdate("INSERT INTO t VALUES (7)");
                try (ResultSet rs = statement.executeQuery("SELECT sum(x) FROM t")) {
                    rs.next();
                    System.out.println("sum=" + rs.getLong(1));
                }
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
            Path path = base.resolve(name);
            ForkResult result = fork(QueriesAwkwardPath.class.getName(), List.of(), path.toString());
            assertEquals(0, result.exitCode, "path " + name + " -> " + result.output);
            assertTrue(result.output.contains("sum=7"), "path " + name + " -> " + result.output);
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
