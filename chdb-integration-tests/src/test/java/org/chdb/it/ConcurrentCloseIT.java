package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Closing a connection, or cancelling a statement, from a thread other than the one running it.
 *
 * <p>Both are ordinary rather than exotic. {@code Statement.cancel()} is defined to be called
 * from another thread and this driver's timeout is built on it; JDBC defines {@code
 * Connection.abort()} as a close from another thread while the connection is in use, and
 * HikariCP calls it on every connection still in use when the pool is closed. What each of
 * these tests pins down came out of one failure in 11 860 iterations of the concurrent soak on
 * {@code origin/main}:
 *
 * <pre>
 * worker 1 shape timeout-open:
 * java.sql.SQLException: Failed to read the next batch:
 *     stream handle 18801 does not belong to connection handle 18576
 *   at org.chdb.jdbc.ChdbResultSet.next(ChdbResultSet.java:121)
 *   at com.zaxxer.hikari.pool.HikariProxyResultSet.next(HikariProxyResultSet.java)
 * </pre>
 *
 * <p>Nothing was mixed up: handle ids are never reused, so the two numbers were this statement's
 * own stream and its own connection. What had happened is that the connection had been closed
 * underneath a live stream, and the shim's ownership check -- which asks whether the id resolves
 * to this stream's owner -- cannot tell "closed" from "somebody else's" and reported the wrong
 * one of the two. Two defects, one per direction:
 *
 * <ol>
 *   <li>Closing a connection while a statement was starting on it left that statement's stream
 *       open, because the close walks the statements and a result set the executing thread has
 *       not assigned yet is not there to be found. Reproduced deterministically below by
 *       closing 0.7 s into a 1.5 s open, on both of the driver's result-set routes.
 *   <li>{@code Statement.cancel()} threw for a statement that had already finished, so the one
 *       call JDBC invites another thread to make could not be made safely. Its cause is
 *       unrelated to the first and its symptom was invisible in every short test: the in-flight
 *       stream registration was compared by boxed identity, so it was cleared correctly for
 *       the first 127 handle ids a process opens and never again.
 * </ol>
 */
class ConcurrentCloseIT extends NativeTestBase {

    /**
     * Boxed {@code Long}s are cached for -128..127 and allocated fresh above that, which is why
     * a defect in an identity comparison on stream handle ids was invisible for the first 127
     * handles a process opened. Every test that would have passed inside the cache runs past it.
     */
    private static final int PAST_THE_BOX_CACHE = 140;

    /**
     * A statement whose open occupies the engine for over a second, on the streaming route.
     * A full aggregate has no first batch until the whole scan is done, so the open -- which
     * fetches the first batch to get the schema -- covers the entire query.
     */
    private static final String SLOW_STREAMING_OPEN =
            "SELECT sum(sipHash64(toString(number))) FROM numbers(200000000)";

    /**
     * The same, on the materialized route: {@code chdb_query_arrow_n} runs the statement to
     * completion before returning, and a scalar subquery is evaluated while EXPLAIN analyses.
     * Included because the two routes reach the shim through different entry points and only
     * the streaming one takes the connection's lock for its first fetch.
     */
    private static final String SLOW_MATERIALIZED_OPEN =
            "EXPLAIN SELECT number FROM numbers(10) WHERE number ="
                    + " (SELECT max(sipHash64(toString(number))) % 10 FROM numbers(200000000))";

    /** Opens and closes result sets until the shim's handle ids are past {@link #PAST_THE_BOX_CACHE}. */
    private static void burnHandles(Connection connection) throws SQLException {
        for (int i = 0; i < PAST_THE_BOX_CACHE; i++) {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                rs.next();
            }
        }
    }

    @Test
    @DisplayName("cancel() after the result set ended is silent, however many handles came before")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void cancelAfterTheStreamEndedIsSilent() throws Exception {
        try (Connection connection = openMemory()) {
            burnHandles(connection);

            // Read to exhaustion: next() returning false is what deregisters the stream.
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(5)")) {
                    while (rs.next()) {
                        rs.getLong(1);
                    }
                }
                // Threw "stream handle N is not open" before the fix, with a null SQLSTATE.
                statement.cancel();
            }

            // And the other way a stream ends: closed early, with rows left.
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000000)")) {
                    rs.next();
                }
                statement.cancel();
            }

            // The connection is still usable, which is the property a pool is deciding about.
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("closing a connection while a statement opens its result set never blames ownership")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void closingDuringAnOpenIsReportedAsAClose() throws Exception {
        assertCloseDuringOpenIsHonest(SLOW_STREAMING_OPEN);
        assertCloseDuringOpenIsHonest(SLOW_MATERIALIZED_OPEN);
    }

    /**
     * Closes a connection while another thread is inside the open, then reads.
     *
     * <p>The close lands strictly between the shim registering the stream and {@code
     * ChdbStatement} assigning it to a result set, which is the window the ownership message
     * came out of: before the fix this reported {@code stream handle N does not belong to
     * connection handle M} on every attempt, on both routes.
     */
    private void assertCloseDuringOpenIsHonest(String sql) throws Exception {
        Connection connection = openMemory();
        CountDownLatch aboutToOpen = new CountDownLatch(1);
        AtomicReference<SQLException> failure = new AtomicReference<>();
        AtomicReference<Boolean> readARow = new AtomicReference<>(Boolean.FALSE);

        Thread reader =
                new Thread(
                        () -> {
                            try (Statement statement = connection.createStatement()) {
                                aboutToOpen.countDown();
                                try (ResultSet rs = statement.executeQuery(sql)) {
                                    readARow.set(rs.next());
                                }
                            } catch (SQLException e) {
                                failure.set(e);
                            }
                        },
                        "concurrent-close-reader");
        reader.start();

        assertTrue(aboutToOpen.await(30, TimeUnit.SECONDS), "the reader never started");
        // Half of the measured open, so the close is inside the engine call rather than
        // before or after it.
        Thread.sleep(700);
        connection.close();
        reader.join(TimeUnit.MINUTES.toMillis(2));
        assertFalse(reader.isAlive(), "the reader never finished");

        SQLException e = failure.get();
        if (e == null) {
            // Legitimate: the reader got its row out before the close reached its statement.
            assertTrue(readARow.get(), "no failure and no row either");
            return;
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        assertFalse(
                message.contains("does not belong"),
                "a closed connection was reported as an ownership violation: " + message);
        assertNotNull(
                e.getSQLState(), "a failure a pool has to classify must carry a SQLSTATE: " + message);
        // HY010 exactly, and this is the assertion that pins the interlock rather than the
        // wording. Every way this can fail is "you used something that is closed": the close
        // waits for the statement start, so the connection's handle is retired only after
        // every stream opened on it has been closed, and a reader can therefore only ever
        // find its *result set* closed. A connection-class SQLSTATE here (08003) would mean
        // the connection went out from under a live stream again -- honestly reported, since
        // that is what the rest of this fix does, but still the thing that must not happen.
        assertEquals(
                "HY010",
                e.getSQLState(),
                "expected the reader to find its result set closed; a connection-class state"
                        + " means the connection was retired with a live stream on it: " + message);
    }

    @Test
    @DisplayName("a read after the connection was closed says the connection was closed")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void readingAfterTheConnectionWasClosedSaysSo() throws Exception {
        Connection connection = openMemory();
        burnHandles(connection);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT number FROM numbers(10000000)");
        assertTrue(rs.next(), "the first batch should arrive");

        // A pool closing a connection it has lent out does this, and so does abort().
        Thread closer =
                new Thread(
                        () -> {
                            try {
                                connection.close();
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        "concurrent-closer");
        closer.start();
        closer.join(TimeUnit.MINUTES.toMillis(1));
        assertFalse(closer.isAlive(), "the closer never finished");

        // The cascade closed the result set, so every method on it must say that -- not
        // relay a native message about handle numbers the caller has never seen.
        assertTrue(rs.isClosed(), "Connection.close() must close the result sets under it");
        for (ThrowingCall call :
                new ThrowingCall[] {
                    () -> rs.next(), () -> rs.getLong(1), () -> rs.getMetaData(), () -> rs.wasNull()
                }) {
            SQLException e = assertThrows(SQLException.class, call::run);
            String message = e.getMessage() == null ? "" : e.getMessage();
            assertFalse(message.contains("does not belong"), "unreadable message: " + message);
            assertTrue(
                    message.contains("closed"),
                    "expected the failure to say the object is closed, got: " + message);
        }
        statement.close();
    }

    @Test
    @DisplayName("a timeout inside the open leaves no result set for the caller to touch")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aTimeoutInTheOpenLeavesNothingReachable() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            // A full aggregate over 500 million rows: measured at 3.9 s on this host, so the
            // one second deadline is reached inside the open, which cannot be interrupted.
            SQLTimeoutException timeout =
                    assertThrows(
                            SQLTimeoutException.class,
                            () ->
                                    statement.execute(
                                            "SELECT sum(sipHash64(toString(number)))"
                                                    + " FROM numbers(500000000)"));
            assertEquals("57014", timeout.getSQLState());

            // The result set was closed and dropped, so there is no way to reach it and no
            // handle left behind -- which is what @AfterEach checks. getResultSet() saying
            // null rather than handing back a closed object is the whole of the answer.
            assertNull(statement.getResultSet(), "a timed-out open must leave no current result set");

            // And the statement is reusable, timeout cleared.
            statement.setQueryTimeout(0);
            try (ResultSet rs = statement.executeQuery("SELECT 7")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
        }
    }

    /** A JDBC call that may throw, for looping over several of them. */
    private interface ThrowingCall {
        void run() throws SQLException;
    }
}
