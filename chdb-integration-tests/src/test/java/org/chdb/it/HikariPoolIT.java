package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The driver under a real connection pool (work plan section 5.10).
 *
 * <p>This is where two of the driver's rules meet something that was not written with them in
 * mind. A pool opens several Connections to the same storage path, which the engine allows; and
 * it hands each one to a different thread, which is exactly the case the one-statement-per-
 * connection rule governs. A pool also validates, evicts and re-opens connections on its own
 * schedule, so the storage-path registry has to survive churn it did not initiate.
 *
 * <p>HikariCP 5.1.0 rather than 6.x: 6.x requires Java 17, and testing it here would quietly
 * drop Java 11 out of the supported matrix.
 */
class HikariPoolIT extends NativeTestBase {

    /** A pool over the shared in-memory database, configured the way an application would. */
    private HikariDataSource pool(int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MEMORY_URL);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(0);
        // Fail fast rather than hanging a test for the default 30 seconds.
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(20));
        config.setInitializationFailTimeout(TimeUnit.SECONDS.toMillis(20));
        config.setPoolName("chdb-it");
        return new HikariDataSource(config);
    }

    @Test
    @DisplayName("Hikari opens a pool over the driver and validates its connections")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void poolStartsAndValidates() throws SQLException {
        try (HikariDataSource dataSource = pool(4)) {
            // Hikari calls isValid() on every connection it hands out. A driver that answered
            // wrongly would either fail here or silently hand out dead connections.
            try (Connection connection = dataSource.getConnection()) {
                assertTrue(connection.isValid(2));
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
            assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @Test
    @DisplayName("several pooled connections to one storage path work concurrently")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void concurrentPooledConnections() throws Exception {
        final int threads = 8;
        final int queriesPerThread = 25;

        try (HikariDataSource dataSource = pool(threads)) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Callable<Long>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                work.add(
                        () -> {
                            long sum = 0;
                            for (int i = 0; i < queriesPerThread; i++) {
                                // Connection per query, returned to the pool each time -- the
                                // access pattern of a web application, and the one that churns
                                // the storage-path registry hardest.
                                try (Connection connection = dataSource.getConnection();
                                        Statement statement = connection.createStatement();
                                        ResultSet rs =
                                                statement.executeQuery(
                                                        "SELECT number FROM numbers(500)")) {
                                    while (rs.next()) {
                                        sum += rs.getLong(1);
                                    }
                                }
                            }
                            return sum;
                        });
            }

            List<Future<Long>> results = executor.invokeAll(work, 4, TimeUnit.MINUTES);
            executor.shutdown();

            long expected = queriesPerThread * (500L * 499L / 2L);
            for (int t = 0; t < threads; t++) {
                // get() rethrows whatever the task threw, which is what makes a driver-level
                // failure show up as a test failure rather than a silently wrong sum.
                assertEquals(expected, results.get(t).get().longValue(), "thread " + t);
            }
        }
    }

    @Test
    @DisplayName("a pool larger than one thread does not deadlock on the one-statement rule")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void poolDoesNotDeadlockOnTheStatementSlot() throws Exception {
        // Each pooled Connection has its own statement slot, so threads holding different
        // connections never wait on each other. The rule only serializes within a Connection,
        // and a pool never shares one across threads -- which is the reason waiting was chosen
        // over rejecting.
        final int threads = 6;
        try (HikariDataSource dataSource = pool(threads)) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger completed = new AtomicInteger();
            List<Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                work.add(
                        () -> {
                            try (Connection connection = dataSource.getConnection();
                                    Statement statement = connection.createStatement();
                                    ResultSet rs =
                                            statement.executeQuery(
                                                    "SELECT number FROM numbers(200000)")) {
                                long rows = 0;
                                while (rs.next()) {
                                    rows++;
                                }
                                assertEquals(200000L, rows);
                            }
                            completed.incrementAndGet();
                            return null;
                        });
            }

            // All six hold an open result set at once. If the slot were per-connection-pool
            // rather than per-connection this would deadlock and the timeout would fire.
            List<Future<Void>> results = executor.invokeAll(work, 2, TimeUnit.MINUTES);
            executor.shutdown();
            for (Future<Void> result : results) {
                result.get();
            }
            assertEquals(threads, completed.get());
        }
    }

    @Test
    @DisplayName("a leaked statement on a returned connection does not poison the next borrower")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void leakedStatementDoesNotPoisonTheConnection() throws SQLException {
        // An application that forgets to close a ResultSet returns the Connection to the pool
        // with its statement slot still held. The next borrower gets the same Connection, and
        // must not inherit a Connection that can never run anything again.
        try (HikariDataSource dataSource = pool(1)) {
            Connection first = dataSource.getConnection();
            Statement leaked = first.createStatement();
            ResultSet leakedResult = leaked.executeQuery("SELECT number FROM numbers(1000000)");
            assertTrue(leakedResult.next());
            // Returned to the pool without closing the result set.
            first.close();

            try (Connection second = dataSource.getConnection();
                    Statement statement = second.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 42")) {
                assertTrue(rs.next(), "the recycled connection must still be usable");
                assertEquals(42, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("Hikari's eviction and re-open cycle leaves the storage path bound correctly")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void connectionChurnKeepsThePathBound() throws Exception {
        // The storage path is released when the last connection closes and re-acquired by the
        // next. A pool that closes down to zero idle and re-opens is exactly that cycle, run
        // repeatedly; getting the refcount wrong here would strand the JVM.
        try (HikariDataSource dataSource = pool(2)) {
            for (int round = 0; round < 20; round++) {
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                }
            }
            dataSource.getHikariPoolMXBean().softEvictConnections();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "the pool must be able to re-open after eviction");
            }
        }
    }

    @Test
    @DisplayName("the pool reports the driver's refusals rather than swallowing them")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void poolSurfacesDriverErrors() throws SQLException {
        try (HikariDataSource dataSource = pool(2);
                Connection connection = dataSource.getConnection()) {
            SQLException e =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            SQLException.class,
                            () -> {
                                try (Statement statement = connection.createStatement()) {
                                    statement.executeQuery("SELECT no_such_column");
                                }
                            });
            assertEquals(47, e.getErrorCode(), "the engine's error code should survive the pool");

            // And the connection is still usable afterwards, so the pool will not discard it.
            assertTrue(connection.isValid(2));
            assertFalse(connection.isClosed());
        }
    }

    @Test
    @DisplayName("a cancel that arrives after the statement finished is silent through the pool")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aLateCancelIsSilentThroughThePool() throws Exception {
        // Statement.cancel() is defined to be callable from another thread, so "the statement
        // finished a moment before you asked" is an ordinary outcome of the race rather than an
        // error -- and the driver reported it as one: "stream handle N is not open", because the
        // in-flight stream registration was compared by boxed identity and so was never cleared
        // for handle ids past the Long cache.
        //
        // Through the pool rather than on a bare connection, because an exception on a pooled
        // connection is not only the caller's problem: it is what the pool inspects to decide
        // whether the connection is still fit to lend out. HikariCP 5.1.0 keeps it for this one
        // -- it evicts on a SQLSTATE of class 08, on SQLTimeoutException and on its own error
        // lists (ProxyConnection.checkException) -- so the identity check below is the sanity
        // half and the assertion at the end is the property. It is asserted at the end rather
        // than at the cancel so that a regression shows both facts.
        try (HikariDataSource dataSource = pool(1)) {
            org.chdb.jdbc.ChdbConnection physical;
            SQLException cancelFailure = null;
            try (Connection pooled = dataSource.getConnection()) {
                physical = pooled.unwrap(org.chdb.jdbc.ChdbConnection.class);

                // Past 127 handles, which is where Long.valueOf stops handing out cached boxes
                // and the identity comparison the registration used stopped matching. Under
                // that number this test passes with the defect in place.
                for (int i = 0; i < 140; i++) {
                    try (Statement statement = pooled.createStatement();
                            ResultSet rs = statement.executeQuery("SELECT 1")) {
                        assertTrue(rs.next());
                    }
                }

                Statement statement = pooled.createStatement();
                try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(5)")) {
                    while (rs.next()) {
                        rs.getLong(1);
                    }
                }
                try {
                    statement.cancel();
                } catch (SQLException e) {
                    cancelFailure = e;
                }
                statement.close();
            }

            try (Connection again = dataSource.getConnection()) {
                assertTrue(
                        again.unwrap(org.chdb.jdbc.ChdbConnection.class) == physical,
                        "the pool threw away a working connection. A cancel with nothing to"
                                + " cancel reported "
                                + (cancelFailure == null
                                        ? "success"
                                        : cancelFailure.getSQLState() + " / "
                                                + cancelFailure.getMessage()));
                try (Statement statement = again.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                }
            }
            assertEquals(
                    null,
                    cancelFailure,
                    "cancel() after the statement finished must be a no-op, not a failure a pool"
                            + " has to interpret");
        }
    }

    @Test
    @DisplayName("a connection the shutdown hook has claimed is evicted, not handed on")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aClaimedConnectionIsEvictedFromThePool() throws SQLException {
        // The end of the chain issue #22 opened. Once the hook has claimed a connection it can
        // never run another statement, so a pool that returned it to the idle set would hand
        // the next caller a connection that fails on use -- forever, for the rest of the
        // process.
        //
        // Worth being precise about what makes this work, because it is not the exception type:
        // HikariCP evicts on getSQLState().startsWith("08"), checked against
        // ProxyConnection.checkException in 5.1.0, so it acts on the SQLSTATE alone and this
        // passed before the refusal was given its JDBC subclass as well. What the subclass buys
        // is callers that catch SQLNonTransientConnectionException instead of reading the
        // string; SqlStateTypeTest covers that. This test covers the pool.
        // Sized to one on purpose: a pool that did not evict has nowhere else to get a
        // connection from, so it would have to hand the claimed one back and the SELECT below
        // would fail. With room for a second, the test could pass without eviction happening.
        try (HikariDataSource dataSource = pool(1)) {
            org.chdb.jdbc.ChdbConnection claimed;
            try (Connection pooled = dataSource.getConnection()) {
                claimed = pooled.unwrap(org.chdb.jdbc.ChdbConnection.class);
                assertTrue(
                        org.chdb.jdbc.ShutdownHookAccess.claim(claimed),
                        "an idle pooled connection is claimable");

                SQLException refused =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                SQLException.class,
                                () -> {
                                    try (Statement statement = pooled.createStatement()) {
                                        statement.executeQuery("SELECT 1");
                                    }
                                });
                assertEquals("08003", refused.getSQLState(), refused.getMessage());
            }

            // Returned to the pool and, because of the SQLSTATE, marked broken on the way. The
            // next caller must get a different physical connection that works.
            try (Connection replacement = dataSource.getConnection();
                    Statement statement = replacement.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "the pool handed out a connection that cannot be used");
                assertFalse(
                        replacement.unwrap(org.chdb.jdbc.ChdbConnection.class) == claimed,
                        "the pool handed back the claimed connection instead of evicting it");
            }
        }
    }
}
