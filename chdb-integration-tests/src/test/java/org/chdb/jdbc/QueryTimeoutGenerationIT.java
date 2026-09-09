package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A query timeout must not reach an execution it was not armed for.
 *
 * <h2>Why this test lives in {@code org.chdb.jdbc}</h2>
 * It calls {@link ChdbStatement#queryTimeoutFired(QueryTimeout, long)} directly, which is
 * package-private. That is not a shortcut around the real path -- it <em>is</em> the real path:
 * the body of the task {@link QueryTimeout#start} schedules is exactly one call to that method
 * and nothing else. So invoking it with a previous execution's number reproduces a stale timer
 * faithfully and deterministically, without a test-only hook in production code and without
 * waiting for a microsecond-wide interleaving to happen by luck.
 *
 * <p>The other half -- that a timeout armed for the execution actually running still fires --
 * is covered end to end by {@code StreamingLifecycleIT.queryTimeout},
 * {@code StreamingLifecycleIT.queryTimeoutDuringTheOpen} and
 * {@code NonStreamableResultsIT.queryTimeoutOnTheMaterializedRoute}, which use the real clock.
 * Between them the guard is pinned in both directions.
 */
class QueryTimeoutGenerationIT {

    @AfterEach
    void assertNoLeakedHandles() {
        assertEquals(0, ChdbNative.openHandleCount(ChdbNative.KIND_STREAM), "leaked stream handles");
        assertEquals(0, ChdbNative.openHandleCount(ChdbNative.KIND_RESULT), "leaked result handles");
        assertEquals(
                0, ChdbNative.openHandleCount(ChdbNative.KIND_CONNECTION), "leaked connection handles");
    }

    /**
     * A timeout scheduled with a long delay, purely to get a distinct instance to hand to the
     * statement. {@link QueryTimeout#NONE} would not do: it is a shared singleton, and a test
     * that reached {@code markExpired()} on it would poison every other statement in the JVM.
     */
    private static QueryTimeout parkedTimeout(ChdbStatement statement, long execution) {
        return QueryTimeout.start(statement, 3600, execution);
    }

    @Test
    @DisplayName("a timeout armed for a finished execution does not touch the next one")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void staleTimeoutDoesNotRejectTheNextStatement() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:")) {
            ChdbStatement statement = (ChdbStatement) connection.createStatement();
            // Long enough that the real timers never fire during the test: every deadline here
            // is delivered by hand.
            statement.setQueryTimeout(3600);

            // Execution 1, run and closed. Its timer is now stale, whether it was stopped in
            // time or not.
            try (ResultSet first = statement.executeQuery("SELECT number FROM numbers(10)")) {
                assertTrue(first.next());
            }
            QueryTimeout staleFromFirst = parkedTimeout(statement, 1);
            try {
                // Execution 2: the statement that must survive.
                ResultSet second = statement.executeQuery("SELECT number FROM numbers(1000)");

                // Execution 1's timer arrives now, mid-execution-2. Before the execution number
                // check this set the shared cancel flag and cancelled execution 2's stream.
                statement.queryTimeoutFired(staleFromFirst, 1);

                assertFalse(
                        staleFromFirst.expired(),
                        "a stale timeout marked itself expired, so timedOut() would misreport");
                assertFalse(
                        statement.wasCancelled(),
                        "a stale timeout set the cancel flag of an execution nobody cancelled");

                // The result set has to still be readable to the end.
                long rows = 0;
                while (second.next()) {
                    assertEquals(rows, second.getLong(1));
                    rows++;
                }
                assertEquals(1000, rows, "a stale timeout truncated the next execution's result");
                second.close();

                // And the statement is still usable for another execution.
                try (ResultSet third = statement.executeQuery("SELECT 7")) {
                    assertTrue(third.next());
                    assertEquals(7, third.getInt(1));
                }
            } finally {
                staleFromFirst.stop();
                statement.close();
            }
        }
    }

    /**
     * The generation check must not swallow an application's own {@code cancel()}. That is the
     * easiest thing to break here: {@code cancel()} is what the timeout path calls, so gating
     * the wrong one would silently make {@code Statement.cancel()} a no-op.
     */
    @Test
    @DisplayName("Statement.cancel() is not filtered by the execution number")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void userCancelIsNotGated() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:")) {
            ChdbStatement statement = (ChdbStatement) connection.createStatement();
            statement.setQueryTimeout(3600);

            // Several executions first, so the execution number is well past 1 and a naive
            // "only act on execution 1" gate would refuse this.
            for (int i = 0; i < 3; i++) {
                try (ResultSet warm = statement.executeQuery("SELECT number FROM numbers(10)")) {
                    assertTrue(warm.next());
                }
            }

            ResultSet rs = statement.executeQuery("SELECT number, sipHash64(number) FROM numbers(100000000)");
            assertTrue(rs.next());
            statement.cancel();
            assertTrue(statement.wasCancelled(), "Statement.cancel() did not register");

            // Reading on must report the cancel rather than quietly returning the rest.
            SQLException raised =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                while (rs.next()) {
                                    rs.getLong(1);
                                }
                            });
            assertEquals("57014", raised.getSQLState(), raised.getMessage());
            assertTrue(
                    raised.getMessage().contains("cancelled"),
                    () -> "expected a cancellation, got: " + raised.getMessage());
            rs.close();
            statement.close();
        }
    }

    /**
     * A stale timeout delivered after the statement is closed is a no-op, and in particular
     * does not throw out of the scheduler thread -- where there is no caller to receive it and
     * an escaping exception would kill the shared timer for every statement in the JVM.
     *
     * <p>Execution number 0 is used because the counter starts at 0 and the first execution
     * makes it 1, so 0 is a number no execution ever had.
     *
     * <p>{@code close()} also calls {@code stopTimeout()} now, so a closed statement leaves
     * nothing in the scheduler queue holding a reference to it. That is a retention fix rather
     * than a correctness one -- the execution number already makes a surviving task harmless --
     * and it is not observable from here, so it is not asserted.
     */
    @Test
    @DisplayName("a stale timeout after close is a no-op and does not escape")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void staleTimeoutAfterCloseIsHarmless() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:")) {
            ChdbStatement statement = (ChdbStatement) connection.createStatement();
            statement.setQueryTimeout(3600);
            try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000000)")) {
                assertTrue(rs.next());
            }
            statement.close();

            QueryTimeout stale = parkedTimeout(statement, 0);
            try {
                statement.queryTimeoutFired(stale, 0);
                assertFalse(stale.expired(), "a stale timeout marked itself expired");
                assertFalse(statement.wasCancelled(), "a stale timeout set the cancel flag");
            } finally {
                stale.stop();
            }
        }
    }

    /**
     * The same delivery while an execution is live, with a number that was never issued: the
     * guard has to reject it on the number rather than on the statement being closed.
     */
    @Test
    @DisplayName("a timeout number that was never issued is rejected")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void unknownTimeoutNumberIsRejected() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:")) {
            ChdbStatement statement = (ChdbStatement) connection.createStatement();
            statement.setQueryTimeout(3600);
            ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000)");
            assertTrue(rs.next());

            QueryTimeout never = parkedTimeout(statement, Long.MAX_VALUE);
            try {
                statement.queryTimeoutFired(never, Long.MAX_VALUE);
                assertFalse(never.expired());
                assertFalse(statement.wasCancelled());
                long rows = 1;
                while (rs.next()) {
                    rows++;
                }
                assertEquals(1000, rows);
            } finally {
                never.stop();
                rs.close();
                statement.close();
            }
        }
    }
}
