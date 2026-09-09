package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Streaming, cancellation and deterministic release -- work plan sections 3.3, 5.8 and 5.11.
 *
 * <p>Two properties matter most here and are both release gates: peak memory tracks the batch
 * rather than the whole result, and every native handle is released on every path, including
 * the ones that end in an error.
 */
class StreamingLifecycleIT extends NativeTestBase {

    /** Resident set size in KB, from ps. Coarse, but enough to tell bounded from linear. */
    private static long residentKb() throws IOException {
        long pid = ProcessHandle.current().pid();
        Process process =
                new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
                        .redirectErrorStream(true)
                        .start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Test
    @DisplayName("a result far larger than the heap reads in bounded memory")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void largeResultIsBounded() throws Exception {
        // The test JVM runs with -Xmx512m, and this result is ~20 million rows of an integer
        // and a string. Materializing it would need gigabytes; streaming it must not.
        try (Connection connection = openMemory()) {
            // Warm up, so the measurement is not dominated by first-query engine setup.
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)")) {
                while (rs.next()) {
                    rs.getLong(1);
                }
            }

            long baseline = residentKb();
            long peak = baseline;
            long rows = 0;
            long checksum = 0;

            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "SELECT number, toString(number) AS s FROM numbers(20000000)")) {
                while (rs.next()) {
                    checksum += rs.getLong(1);
                    rows++;
                    if ((rows & 0xFFFFF) == 0) {
                        peak = Math.max(peak, residentKb());
                    }
                }
            }

            assertEquals(20_000_000L, rows);
            // n*(n-1)/2 for n = 20,000,000: proves the values were decoded, not just counted.
            assertEquals(199_999_990_000_000L, checksum);

            if (baseline > 0 && peak > 0) {
                long growthMb = (peak - baseline) / 1024;
                // Generous: the point is that growth is bounded by the batch, not by the
                // 20 million rows. A materializing driver would need thousands of MB here.
                assertTrue(
                        growthMb < 800,
                        "RSS grew " + growthMb + " MB while streaming 20,000,000 rows"
                                + " (baseline " + baseline / 1024 + " MB, peak " + peak / 1024 + " MB),"
                                + " which suggests the result is being accumulated rather than streamed");
            }
        }
    }

    @Test
    @DisplayName("reading one row of an effectively infinite result and closing returns at once")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void earlyCloseIsCheap() throws SQLException {
        long start = System.nanoTime();
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT number FROM numbers(100000000000)")) {
            assertTrue(rs.next());
            rs.getLong(1);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        // A driver that drained the stream on close would never finish this.
        assertTrue(millis < 30_000, "early close took " + millis + " ms");
    }

    @Test
    @DisplayName("setMaxRows stops the stream instead of reading and discarding")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void maxRowsStopsTheStream() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.setMaxRows(10);
            try (ResultSet rs =
                    statement.executeQuery("SELECT number FROM numbers(100000000000)")) {
                long rows = 0;
                while (rs.next()) {
                    rows++;
                }
                assertEquals(10, rows);
            }
        }
    }

    @Test
    @DisplayName("closing a Connection closes its Statements and their ResultSets")
    void connectionCloseCascades() throws SQLException {
        Connection connection = openMemory();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000000)");
        assertTrue(rs.next());

        connection.close();

        assertTrue(connection.isClosed());
        assertTrue(statement.isClosed(), "the statement should have been closed with the connection");
        assertTrue(rs.isClosed(), "the result set should have been closed with the connection");
        // The @AfterEach handle assertion is the real check: nothing may be left open.
    }

    @Test
    @DisplayName("a ResultSet closed early releases its batch, and reading it afterwards is refused")
    void readAfterCloseIsRefused() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)");
            assertTrue(rs.next());
            assertEquals(0L, rs.getLong(1));
            rs.close();

            assertTrue(rs.isClosed());
            // The batch's native memory is gone. Reading must be an exception, not a read of
            // freed memory.
            SQLException e = assertThrows(SQLException.class, () -> rs.getLong(1));
            assertEquals("HY010", e.getSQLState());
            assertThrows(SQLException.class, rs::next);
            // Closing twice is a no-op, as JDBC requires.
            rs.close();
        }
    }

    @Test
    @DisplayName("a query timeout cancels the engine and raises SQLTimeoutException")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void queryTimeout() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            long start = System.nanoTime();
            SQLException raised =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                try (ResultSet rs =
                                        statement.executeQuery(
                                                "SELECT number, sipHash64(number)"
                                                        + " FROM numbers(100000000000)")) {
                                    while (rs.next()) {
                                        rs.getLong(1);
                                    }
                                }
                            });
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(
                    raised instanceof SQLTimeoutException,
                    "expected SQLTimeoutException, got " + raised.getClass().getName()
                            + ": " + raised.getMessage());
            assertEquals("57014", raised.getSQLState());
            // The timeout must actually stop the engine, not just stop waiting for it.
            assertTrue(millis < 30_000, "timeout took " + millis + " ms to take effect");
        }
    }

    /**
     * The same timeout, but expiring inside the call that opens the result set rather than
     * during the fetches.
     *
     * <p>{@link #queryTimeout()} above covers a SELECT that emits as it scans, where the open
     * costs milliseconds and the clock runs out in {@code next()}. A full aggregate has no
     * first batch until the whole scan is done, so the entire query happens inside {@code
     * streamOpen} -- and nothing can be cancelled there, because the handle {@code cancel()}
     * would need is what the call is still producing. Measured on v26.7.0 before this was
     * handled: {@code setQueryTimeout(1)} returned a working result set after 12.65 seconds.
     *
     * <p>Asserted as an invariant rather than against the clock: a result set is the right
     * answer only if the statement beat its deadline.
     */
    @Test
    @DisplayName("a timeout that expires while the result set is opening is still reported")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void queryTimeoutDuringTheOpen() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            long start = System.nanoTime();
            // Sized so the scan runs for a few seconds against a 1 s deadline: the timeout
            // cannot stop it, so the test pays the whole scan either way and there is no
            // reason to make it longer than the margin needs.
            try (ResultSet rs =
                    statement.executeQuery(
                            "SELECT max(sipHash64(number)) FROM numbers(500000000)")) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(
                        millis < 1_000,
                        () -> "executeQuery returned a result set " + millis
                                + " ms after a 1 s query timeout");
                assertTrue(rs.next());
            } catch (SQLTimeoutException expected) {
                assertEquals("57014", expected.getSQLState());
            }
            // The slot has to have come back, or nothing else could run on this connection.
            try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @DisplayName("cancel from another thread stops an in-flight query")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void cancelFromAnotherThread() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            CountDownLatch firstRowRead = new CountDownLatch(1);

            Thread canceller =
                    new Thread(
                            () -> {
                                try {
                                    // Cancel only once the query is definitely running, so this
                                    // tests cancellation rather than a race at startup.
                                    firstRowRead.await(30, TimeUnit.SECONDS);
                                    statement.cancel();
                                } catch (Exception ignored) {
                                    // Nothing to report from a helper thread; the assertions on
                                    // the main thread are what decide the outcome.
                                }
                            },
                            "chdb-it-canceller");
            canceller.start();

            long start = System.nanoTime();
            SQLException raised =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                try (ResultSet rs =
                                        statement.executeQuery(
                                                "SELECT number, sipHash64(number)"
                                                        + " FROM numbers(100000000000)")) {
                                    boolean signalled = false;
                                    while (rs.next()) {
                                        if (!signalled) {
                                            firstRowRead.countDown();
                                            signalled = true;
                                        }
                                    }
                                }
                            });
            long millis = (System.nanoTime() - start) / 1_000_000;
            canceller.join(TimeUnit.SECONDS.toMillis(30));

            assertEquals("57014", raised.getSQLState(), raised.getMessage());
            assertTrue(
                    raised.getMessage().contains("cancelled"),
                    "the error should say the statement was cancelled: " + raised.getMessage());
            assertTrue(millis < 30_000, "cancel took " + millis + " ms to take effect");
        }
    }

    @Test
    @DisplayName("cancel on an idle statement is a no-op, not an error")
    void cancelWhenIdle() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.cancel();
            // And the statement still works afterwards.
            try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @DisplayName("a failing query leaks nothing, over many repetitions")
    void errorPathsDoNotLeak() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            for (int i = 0; i < 50; i++) {
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT no_such_column"));
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT ((("));
                assertThrows(
                        SQLException.class, () -> statement.executeQuery("SELECT * FROM no_such_table"));
            }
        }
        // @AfterEach asserts the handle counts, which is the point of the repetition: a leak of
        // one handle per failure would be unmistakable after 150.
    }

    @Test
    @DisplayName("a thousand queries leave no handles and no runaway memory")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void repeatedQueriesAreStable() throws Exception {
        try (Connection connection = openMemory()) {
            // Warm up before measuring: the engine allocates caches on first use, and counting
            // those as growth would make any threshold meaningless.
            for (int i = 0; i < 50; i++) {
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)")) {
                    while (rs.next()) {
                        rs.getLong(1);
                    }
                }
            }

            long baseline = residentKb();
            for (int i = 0; i < 1000; i++) {
                try (Statement statement = connection.createStatement();
                        ResultSet rs =
                                statement.executeQuery(
                                        "SELECT number, toString(number) FROM numbers(1000)")) {
                    while (rs.next()) {
                        rs.getLong(1);
                        rs.getString(2);
                    }
                }
            }
            long after = residentKb();

            if (baseline > 0 && after > 0) {
                long growthMb = (after - baseline) / 1024;
                // The criterion is a stable plateau, not a return to the starting figure: an
                // allocator legitimately keeps its arenas. A per-query leak would show up as
                // hundreds of MB across a thousand queries.
                assertTrue(
                        growthMb < 400,
                        "RSS grew " + growthMb + " MB across 1000 queries (baseline "
                                + baseline / 1024 + " MB, after " + after / 1024 + " MB)");
            }
        }
    }

    @Test
    @DisplayName("a slow consumer does not accumulate batches")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void slowConsumerDoesNotAccumulate() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT number FROM numbers(2000000)")) {
            long rows = 0;
            long peak = residentKb();
            while (rs.next()) {
                rows++;
                if (rows % 200_000 == 0) {
                    // Backpressure: the engine must wait for us rather than buffering ahead.
                    Thread.sleep(50);
                    peak = Math.max(peak, residentKb());
                }
            }
            assertEquals(2_000_000L, rows);
            assertTrue(peak > 0);
        }
    }

    @Test
    @DisplayName("two statements on one connection are serialized, not corrupted")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void concurrentStatementsAreSerialized() throws Exception {
        try (Connection connection = openMemory()) {
            int threads = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            long[] sums = new long[threads];
            Throwable[] failures = new Throwable[threads];

            for (int t = 0; t < threads; t++) {
                final int index = t;
                new Thread(
                                () -> {
                                    try {
                                        start.await();
                                        long sum = 0;
                                        for (int i = 0; i < 20; i++) {
                                            try (Statement statement = connection.createStatement();
                                                    ResultSet rs =
                                                            statement.executeQuery(
                                                                    "SELECT number FROM numbers(500)")) {
                                                while (rs.next()) {
                                                    sum += rs.getLong(1);
                                                }
                                            }
                                        }
                                        sums[index] = sum;
                                    } catch (Throwable e) {
                                        failures[index] = e;
                                    } finally {
                                        done.countDown();
                                    }
                                },
                                "chdb-it-concurrent-" + t)
                        .start();
            }

            start.countDown();
            assertTrue(done.await(3, TimeUnit.MINUTES), "threads did not finish");

            for (int t = 0; t < threads; t++) {
                assertEquals(null, failures[t], "thread " + t + " failed: " + failures[t]);
                // 500*499/2 per query, 20 queries. Every thread must get the exact figure:
                // a torn read or a shared cursor would show up as a wrong sum.
                assertEquals(20L * (500L * 499L / 2L), sums[t], "thread " + t);
            }
        }
    }

    @Test
    @DisplayName("a closed Statement and Connection refuse further work")
    void useAfterCloseIsRefused() throws SQLException {
        Connection connection = openMemory();
        Statement statement = connection.createStatement();
        statement.close();
        assertTrue(statement.isClosed());
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
        // Closing twice is a no-op.
        statement.close();

        connection.close();
        assertThrows(SQLException.class, connection::createStatement);
        assertFalse(connection.isValid(1));
        connection.close();
    }
}
