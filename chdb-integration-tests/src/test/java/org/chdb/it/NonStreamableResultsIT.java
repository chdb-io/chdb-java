package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.chdb.internal.ChdbNative;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Result sets the engine refuses to stream -- issue #12.
 *
 * <p>{@code SHOW}, {@code DESCRIBE}, {@code DESC}, {@code EXPLAIN}, {@code EXISTS} and {@code
 * CHECK} all produce rows, and none of them parses as the SELECT pipeline {@code
 * chdb_stream_query_arrow_n} requires, so every one of them used to fail with {@code Streaming
 * query is not supported}. They go through {@code chdb_query_arrow_n} instead, which exports
 * the same Arrow C Data Interface materialized.
 *
 * <p>The assertions are on column names, JDBC types and row content rather than on "no
 * exception was thrown", because the failure this guards against is not only an error: routing
 * a statement through the wrong door can also produce a result set with the wrong shape.
 *
 * <p>{@link #insertIsNeverRunTwice()} is the other half. A statement whose leading keyword says
 * "result set" can still be a write -- {@code WITH q AS (...) INSERT INTO t SELECT ...} is one
 * -- so the driver must not have a route that re-runs a refused statement. That is asserted as a
 * per-call row-count delta on a table where a duplicate write is visible, not against an
 * exception type.
 */
class NonStreamableResultsIT extends NativeTestBase {

    private static final String DB = "issue12";

    /**
     * Creates the fixture and returns a statement on it.
     *
     * <p>Per test rather than once per class: {@code :memory:} state does not outlive the
     * connection that created it, and a connection held open across tests would trip {@link
     * NativeTestBase#assertNoLeakedHandles()}.
     */
    private static Statement fixture(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.execute("CREATE DATABASE IF NOT EXISTS " + DB);
        statement.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + DB
                        + ".d (x Int32, s String) ENGINE = MergeTree ORDER BY x");
        statement.execute("INSERT INTO " + DB + ".d VALUES (1, 'a'), (2, 'b')");
        return statement;
    }

    // ------------------------------------------------------------------ DESCRIBE / DESC

    @Test
    @DisplayName("DESCRIBE TABLE reports the columns with the engine's own schema")
    void describeTable() throws Exception {
        assertDescribes("DESCRIBE TABLE " + DB + ".d");
    }

    @Test
    @DisplayName("DESC is the same statement and gives the same result set")
    void descShorthand() throws Exception {
        assertDescribes("DESC " + DB + ".d");
    }

    @Test
    @DisplayName("DESCRIBE of a subquery describes its projection")
    void describeSubquery() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("DESCRIBE (SELECT 1 AS q, 'z' AS w)")) {
            assertEquals("name", rs.getMetaData().getColumnName(1));
            assertTrue(rs.next());
            assertEquals("q", rs.getString("name"));
            assertEquals("UInt8", rs.getString("type"));
            assertTrue(rs.next());
            assertEquals("w", rs.getString("name"));
            assertEquals("String", rs.getString("type"));
            assertFalse(rs.next());
        }
    }

    /** DESCRIBE's seven-column result set, asserted down to the row content. */
    private static void assertDescribes(String sql) throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery(sql)) {
            // getMetaData() must answer before the first next(), which is why the shim fetches
            // the first batch at open on this route as well as on the streaming one.
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(7, meta.getColumnCount(), sql);
            assertEquals(
                    List.of(
                            "name",
                            "type",
                            "default_type",
                            "default_expression",
                            "comment",
                            "codec_expression",
                            "ttl_expression"),
                    columnNames(meta),
                    sql);
            for (int i = 1; i <= 7; i++) {
                assertEquals(Types.VARCHAR, meta.getColumnType(i), sql + " column " + i);
                assertEquals("String", meta.getColumnTypeName(i), sql + " column " + i);
            }

            assertTrue(rs.next(), sql);
            assertEquals("x", rs.getString("name"), sql);
            assertEquals("Int32", rs.getString("type"), sql);
            assertEquals("", rs.getString("default_type"), sql);
            assertTrue(rs.next(), sql);
            assertEquals("s", rs.getString("name"), sql);
            assertEquals("String", rs.getString("type"), sql);
            assertFalse(rs.next(), sql);
        }
    }

    // ------------------------------------------------------------------ SHOW

    @Test
    @DisplayName("SHOW TABLES lists the fixture table")
    void showTables() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery("SHOW TABLES FROM " + DB)) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(1, meta.getColumnCount());
            assertEquals("name", meta.getColumnName(1));
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertTrue(rs.next());
            assertEquals("d", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    @DisplayName("SHOW DATABASES lists the fixture database")
    void showDatabases() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
            assertEquals("name", rs.getMetaData().getColumnName(1));
            assertEquals(Types.VARCHAR, rs.getMetaData().getColumnType(1));
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            // system is always there, and so is the fixture; the rest of the list is not this
            // test's business.
            assertTrue(names.contains(DB), () -> "SHOW DATABASES did not list " + DB + ": " + names);
            assertTrue(names.contains("system"), () -> "SHOW DATABASES did not list system: " + names);
        }
    }

    @Test
    @DisplayName("SHOW CREATE TABLE returns the DDL as one row")
    void showCreateTable() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery("SHOW CREATE TABLE " + DB + ".d")) {
            assertEquals("statement", rs.getMetaData().getColumnName(1));
            assertEquals(Types.VARCHAR, rs.getMetaData().getColumnType(1));
            assertTrue(rs.next());
            String ddl = rs.getString(1);
            // Multi-line, so this is also the one assertion here that a newline survives the
            // Arrow round trip on this route.
            assertTrue(ddl.startsWith("CREATE TABLE " + DB + ".d"), ddl);
            assertTrue(ddl.contains("`x` Int32"), ddl);
            assertTrue(ddl.contains("ENGINE = MergeTree"), ddl);
            assertFalse(rs.next());
        }
    }

    @Test
    @DisplayName("SHOW COLUMNS describes the table in MySQL's shape")
    void showColumns() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery("SHOW COLUMNS FROM d FROM " + DB)) {
            assertEquals(
                    List.of("field", "type", "null", "key", "default", "extra"),
                    columnNames(rs.getMetaData()));
            List<String> fields = new ArrayList<>();
            while (rs.next()) {
                fields.add(rs.getString("field"));
            }
            // SHOW COLUMNS orders by name, not by ordinal.
            assertEquals(List.of("s", "x"), fields);
        }
    }

    @Test
    @DisplayName("SHOW SETTINGS returns the setting it was asked for")
    void showSettings() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SHOW SETTINGS LIKE 'max_threads'")) {
            assertEquals(List.of("name", "type", "value"), columnNames(rs.getMetaData()));
            assertTrue(rs.next());
            assertEquals("max_threads", rs.getString("name"));
            assertNotNull(rs.getString("value"));
        }
    }

    // ------------------------------------------------------------------ EXISTS

    @Test
    @DisplayName("EXISTS TABLE answers 1 and 0 as a UInt8 column named result")
    void existsTable() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection)) {
            try (ResultSet rs = statement.executeQuery("EXISTS TABLE " + DB + ".d")) {
                ResultSetMetaData meta = rs.getMetaData();
                assertEquals(1, meta.getColumnCount());
                assertEquals("result", meta.getColumnName(1));
                assertEquals("UInt8", meta.getColumnTypeName(1));
                assertEquals(Types.SMALLINT, meta.getColumnType(1));
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertFalse(rs.next());
            }
            try (ResultSet rs = statement.executeQuery("EXISTS TABLE " + DB + ".no_such_table")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("result"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    @DisplayName("EXISTS DATABASE answers the same way")
    void existsDatabase() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection)) {
            try (ResultSet rs = statement.executeQuery("EXISTS DATABASE " + DB)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("result"));
            }
            try (ResultSet rs = statement.executeQuery("EXISTS DATABASE no_such_database")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("result"));
            }
        }
    }

    // ------------------------------------------------------------------ EXPLAIN

    @Test
    @DisplayName("every EXPLAIN form returns its own plan text")
    void explainForms() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            // Each form's output is the engine's, not the driver's, so these assert one
            // distinctive line per form rather than the whole plan -- enough to prove the
            // right statement ran and its rows arrived intact.
            assertExplainContains(statement, "EXPLAIN SELECT 1", "ReadFromSystemOne");
            assertExplainContains(statement, "EXPLAIN PLAN SELECT 1", "ReadFromSystemOne");
            assertExplainContains(statement, "EXPLAIN AST SELECT 1", "SelectWithUnionQuery");
            assertExplainContains(statement, "EXPLAIN SYNTAX SELECT 1", "FROM system.one");
            assertExplainContains(statement, "EXPLAIN PIPELINE SELECT 1", "ExpressionTransform");
            assertExplainContains(statement, "EXPLAIN QUERY TREE SELECT 1", "PROJECTION COLUMNS");
        }
    }

    /** {@code EXPLAIN <form>} produces one String column named {@code explain}. */
    private static void assertExplainContains(Statement statement, String sql, String expected)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(1, meta.getColumnCount(), sql);
            assertEquals("explain", meta.getColumnName(1), sql);
            assertEquals(Types.VARCHAR, meta.getColumnType(1), sql);

            List<String> lines = new ArrayList<>();
            while (rs.next()) {
                lines.add(rs.getString("explain"));
            }
            assertFalse(lines.isEmpty(), sql + " returned no rows");
            assertTrue(
                    lines.stream().anyMatch(line -> line != null && line.contains(expected)),
                    () -> sql + " did not mention " + expected + ": " + lines);
        }
    }

    @Test
    @DisplayName("EXPLAIN ESTIMATE returns its numeric columns, and an empty result set works")
    void explainEstimate() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection)) {
            try (ResultSet rs =
                    statement.executeQuery(
                            "EXPLAIN ESTIMATE SELECT * FROM " + DB + ".d WHERE x = 1")) {
                ResultSetMetaData meta = rs.getMetaData();
                assertEquals(
                        List.of("database", "table", "parts", "rows", "marks"), columnNames(meta));
                assertEquals(Types.VARCHAR, meta.getColumnType(1));
                // UInt64, which does not fit a signed long, so the driver reports NUMERIC.
                assertEquals(Types.NUMERIC, meta.getColumnType(3));
                assertEquals("UInt64", meta.getColumnTypeName(3));
                assertTrue(rs.next());
                assertEquals(DB, rs.getString("database"));
                assertEquals("d", rs.getString("table"));
                assertEquals(1L, rs.getLong("parts"));
                assertFalse(rs.next());
            }

            // Trivial count() is answered from metadata, so there is nothing to estimate.
            // A zero-row materialized result set is its own case in the engine's Arrow export
            // (arrow::Table::MakeEmpty), and it has to keep the schema.
            try (ResultSet rs =
                    statement.executeQuery("EXPLAIN ESTIMATE SELECT count() FROM " + DB + ".d")) {
                assertEquals(
                        List.of("database", "table", "parts", "rows", "marks"),
                        columnNames(rs.getMetaData()));
                assertFalse(rs.next());
            }
        }
    }

    // ------------------------------------------------------------------ CHECK

    @Test
    @DisplayName("CHECK TABLE reports one row per part, all passing")
    void checkTable() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection);
                ResultSet rs = statement.executeQuery("CHECK TABLE " + DB + ".d")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(List.of("part_path", "is_passed", "message"), columnNames(meta));
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertEquals(Types.SMALLINT, meta.getColumnType(2));

            int parts = 0;
            while (rs.next()) {
                parts++;
                assertEquals(1, rs.getInt("is_passed"), rs.getString("message"));
                assertNotNull(rs.getString("part_path"));
            }
            assertTrue(parts > 0, "CHECK TABLE returned no parts");
        }
    }

    // ------------------------------------------------------------------ TABLE shorthand

    /**
     * {@code TABLE t} is in the issue's list, and the honest answer is that this engine does not
     * implement it: v26.7.0 parses {@code TABLE d} as the expression {@code SELECT TABLE AS d},
     * so it fails on name resolution. That is a statement the engine does not have rather than a
     * routing bug, and the distinction is worth pinning: the driver sends it to the streaming
     * door, the engine's admission test accepts the SELECT it parsed, and what comes back is the
     * engine's own error rather than {@code Streaming query is not supported}.
     */
    @Test
    @DisplayName("TABLE t is not a statement this engine has, and fails on its own terms")
    void tableShorthandIsNotSupportedByTheEngine() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection)) {
            statement.execute("CREATE TABLE IF NOT EXISTS issue12_plain (x Int32) ENGINE = Memory");

            // Unqualified, so the parser gets as far as resolving the name it read: it is
            // looking for a column called TABLE, which is the proof that it parsed a SELECT.
            SQLException unqualified =
                    assertThrows(
                            SQLException.class, () -> statement.executeQuery("TABLE issue12_plain"));
            assertEquals(47, unqualified.getErrorCode(), unqualified.getMessage());
            assertTrue(
                    unqualified.getMessage().contains("SELECT `TABLE` AS issue12_plain"),
                    () -> "unexpected failure for TABLE t: " + unqualified.getMessage());

            // Qualified, where it does not even parse.
            SQLException qualified =
                    assertThrows(
                            SQLException.class, () -> statement.executeQuery("TABLE " + DB + ".d"));
            assertEquals(62, qualified.getErrorCode(), qualified.getMessage());

            // Neither is a routing failure, which is the point of asserting on them at all.
            for (SQLException failure : List.of(unqualified, qualified)) {
                assertFalse(
                        failure.getMessage().contains("Streaming query is not supported"),
                        () -> "TABLE t was refused by the streaming door: " + failure.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ no re-execution

    /**
     * No route may run a statement twice, and none may run a write the caller sent down a
     * path that returns a result set.
     *
     * <p>Asserted as a per-call delta on a table where a duplicate write is visible in {@code
     * count()}: a call that throws must move the count by 0, and a call that succeeds by
     * exactly 1. Never 2. That is the property, and it does not depend on which engine is
     * pinned -- which matters, because the answer for the dangerous statement below does.
     *
     * <p>{@code WITH q AS (...) INSERT INTO t SELECT ...} parses, writes, and has a leading
     * keyword the scan calls a result set. On the v26.7.2-rc.2 baseline {@code
     * chdb_classify_query_n} reports it MUTATING, so it is routed to {@code chdb_query_n} and
     * simply works. Without the classifier the keyword scan sends it to the streaming door,
     * which refuses it, and it is reported rather than retried -- which is the whole reason
     * this route is chosen by a keyword scan and not by a fallback. Either way it is executed
     * at most once, which is what this asserts.
     */
    @Test
    @DisplayName("no route runs an INSERT twice")
    void insertIsNeverRunTwice() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + DB);
            statement.execute(
                    "CREATE TABLE " + DB + ".audit (v Int32) ENGINE = MergeTree ORDER BY tuple()");

            // Control: this table shows duplicate writes, so every delta below means something.
            assertEquals(1, statement.executeUpdate("INSERT INTO " + DB + ".audit VALUES (1)"));
            assertEquals(1, countAudit(statement));
            assertEquals(1, statement.executeUpdate("INSERT INTO " + DB + ".audit VALUES (1)"));
            assertEquals(2, countAudit(statement), "the fixture cannot observe a duplicate write");

            String writeBehindAResultSetKeyword =
                    "WITH q AS (SELECT 7 AS v) INSERT INTO " + DB + ".audit SELECT v FROM q";

            // executeQuery must never execute a write, on any engine: with the classifier it is
            // refused as "does not return a result set" before anything runs, and without it the
            // streaming door refuses it and the driver does not retry.
            assertWritesAtMostOnce(
                    statement,
                    "executeQuery",
                    0,
                    () -> {
                        // Closed if it ever returns one, so a driver bug shows up as the delta
                        // assertion below rather than as a leaked handle.
                        try (ResultSet ignored =
                                statement.executeQuery(writeBehindAResultSetKeyword)) {
                            // no rows to read: reaching here is already the failure
                        }
                    });

            // execute() and executeUpdate() may legitimately run it -- once.
            assertWritesAtMostOnce(
                    statement, "execute", 7, () -> statement.execute(writeBehindAResultSetKeyword));
            assertWritesAtMostOnce(
                    statement,
                    "executeUpdate",
                    7,
                    () -> statement.executeUpdate(writeBehindAResultSetKeyword));

            // And an ordinary INSERT sent to executeQuery is refused by the driver before it
            // reaches the engine at all, so the row never lands.
            assertWritesAtMostOnce(
                    statement,
                    "executeQuery on a plain INSERT",
                    0,
                    () -> {
                        try (ResultSet ignored =
                                statement.executeQuery("INSERT INTO " + DB + ".audit VALUES (9)")) {
                            // as above
                        }
                    });
            assertEquals(0, countAudit(statement, 9), "executeQuery executed an INSERT");
        }
    }

    /**
     * Runs {@code call} and asserts it wrote 0 rows if it threw and exactly 1 if it did not --
     * never 2, which is what a route that re-ran the statement would produce.
     *
     * @param expectedValue the value the write would insert, checked for the same delta, or 0 to
     *     skip that check
     */
    private static void assertWritesAtMostOnce(
            Statement statement, String label, int expectedValue, ThrowingCall call)
            throws SQLException {
        long before = countAudit(statement);
        long beforeValue = expectedValue == 0 ? 0 : countAudit(statement, expectedValue);
        boolean threw = false;
        try {
            call.run();
        } catch (SQLException e) {
            threw = true;
        }
        long delta = countAudit(statement) - before;
        boolean failed = threw;
        assertEquals(
                failed ? 0L : 1L,
                delta,
                () -> label + (failed ? " threw but still wrote " : " wrote ") + delta + " rows");
        if (expectedValue != 0) {
            assertEquals(
                    failed ? 0L : 1L,
                    countAudit(statement, expectedValue) - beforeValue,
                    label + " did not write exactly one row with v = " + expectedValue);
        }
    }

    private interface ThrowingCall {
        void run() throws SQLException;
    }

    private static long countAudit(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT count() FROM " + DB + ".audit")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static long countAudit(Statement statement, int value) throws SQLException {
        try (ResultSet rs =
                statement.executeQuery(
                        "SELECT count() FROM " + DB + ".audit WHERE v = " + value)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ the route's limits

    /**
     * {@code chdb_query_arrow_n} has no {@code _with_params_n} variant, so a non-streamable
     * statement with server-side bindings has no route. The driver says so rather than
     * interpolating the value into the SQL, which is the injection its server-side binding
     * exists to avoid.
     */
    @Test
    @DisplayName("a parameterized non-streamable statement is refused, not interpolated")
    void parameterizedNonStreamableStatementIsRefused() throws Exception {
        try (Connection connection = openMemory();
                PreparedStatement prepared =
                        connection.prepareStatement("SHOW TABLES FROM " + DB + " LIKE ?")) {
            prepared.setString(1, "d");
            SQLException failure = assertThrows(SQLException.class, prepared::executeQuery);
            assertTrue(
                    failure.getMessage().contains("Server-side parameters are not supported"),
                    () -> "unexpected message: " + failure.getMessage());
            assertEquals("0A000", failure.getSQLState());
        }
    }

    /**
     * A query timeout has to be reported even though the materialized route cannot be
     * interrupted.
     *
     * <p>{@code chdb_query_arrow_n} runs the statement to completion before it returns, and
     * every cancel the C ABI exports takes a handle the call is still producing -- so there is
     * nothing for the timer thread to cancel. Before this was handled, {@code executeQuery}
     * came back <em>successfully</em>, with a full result set, after the deadline had passed.
     *
     * <p>The assertion is on the invariant rather than on the clock, so it cannot go flaky on a
     * machine of any speed: either the statement beat the deadline, in which case a result set
     * is the right answer, or it did not, in which case a result set is never the right answer.
     *
     * <p>Schema inference is the only non-streamable statement whose cost the test can dial:
     * measured at ~10.7 ms per megabyte of input on v26.7.0, independent of row and column
     * count, so 180 MB buys about 1.5 s against a 1 s deadline.
     */
    @Test
    @DisplayName("a query timeout on the materialized route is reported, not silently ignored")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void queryTimeoutOnTheMaterializedRoute(@TempDir Path tempDir) throws Exception {
        Path jsonl = tempDir.resolve("infer.jsonl");
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO FUNCTION file('"
                            + jsonl
                            + "', 'JSONEachRow') SELECT number AS n, toString(number) AS s"
                            + " FROM numbers(6000000)");

            // Raised past their defaults on purpose: inference reads only 25k rows otherwise,
            // and the point is a statement that takes real time inside the open.
            String describe =
                    "DESCRIBE file('"
                            + jsonl
                            + "', 'JSONEachRow') SETTINGS"
                            + " input_format_max_rows_to_read_for_schema_inference = 6000000,"
                            + " input_format_max_bytes_to_read_for_schema_inference = 100000000000";

            statement.setQueryTimeout(1);
            long start = System.nanoTime();
            try (ResultSet rs = statement.executeQuery(describe)) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(
                        millis < 1_000,
                        () -> "executeQuery returned a result set " + millis
                                + " ms after a 1 s query timeout");
                assertTrue(rs.next());
            } catch (SQLTimeoutException expected) {
                assertEquals("57014", expected.getSQLState());
                assertTrue(
                        expected.getMessage().contains("could not be interrupted"),
                        () -> "the message should say why nothing was cancelled: "
                                + expected.getMessage());
            }
        }
    }

    /**
     * A materialized result of tens of thousands of rows arrives whole.
     *
     * <p>Every other test here reads a handful of rows, which would not notice a result
     * truncated at the point the engine's Arrow export changes shape. Measured on v26.7.0: the
     * engine builds each of these statements as a single block, so 30,001 rows of {@code SHOW
     * TABLES} and 24,005 rows of {@code EXPLAIN AST} both come back as exactly one Arrow batch
     * -- the multi-batch path {@code chdb_query_arrow_n} does take for a large SELECT (16
     * batches at a million rows) is not reachable from any statement that needs this route.
     * That is worth knowing rather than assuming, and this test is what pins the row count.
     */
    @Test
    @DisplayName("a materialized result of tens of thousands of rows is not truncated")
    void largeMaterializedResult() throws Exception {
        int unions = 8000;
        StringBuilder sql = new StringBuilder("EXPLAIN AST SELECT 0");
        for (int i = 1; i <= unions; i++) {
            sql.append(" UNION ALL SELECT ").append(i);
        }
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql.toString())) {
            assertEquals("explain", rs.getMetaData().getColumnName(1));
            long rows = 0;
            long literals = 0;
            while (rs.next()) {
                String line = rs.getString("explain");
                assertNotNull(line, "row " + rows + " of the plan was null");
                rows++;
                if (line.contains("Literal UInt64_")) {
                    literals++;
                }
            }
            // One AST literal per branch of the union: the row count is the engine's, but the
            // literals are ours to count, and a truncated result loses them.
            assertEquals(unions + 1, literals, "the plan lost branches; it had " + rows + " rows");
            assertTrue(rows > 20_000, "expected a five-figure plan, got " + rows + " rows");
        }
    }

    /**
     * A materialized statement still works after a streaming result set was abandoned half-read.
     *
     * <p>This looks like a redundant pairing and is not. Measured on v26.7.2-rc.2 by driving the
     * shim directly: opening a stream and closing it <em>without</em> cancelling leaves the
     * engine in streaming mode, and the next {@code chdb_query_arrow_n} on that connection comes
     * back with {@code Streaming query is not supported for query: SHOW DATABASES} -- the
     * streaming refusal, from the materialized entry point. Cancelling first clears it, and so
     * does draining the stream to its end.
     *
     * <p>The driver is safe because {@code ChdbResultSet.close()} already cancels when the
     * stream is not exhausted, for an unrelated reason: not making the engine finish a query
     * nobody is reading. So the new route depends on that cancel for its correctness, which was
     * not true of anything before it and is not obvious from either side. "Read one row of a
     * huge result and close" is the documented cheap case, and a `SHOW TABLES` right after it is
     * what a JDBC GUI does; if that cancel were ever dropped as an optimisation, this test is
     * what would notice.
     */
    @Test
    @DisplayName("a materialized statement works after an abandoned streaming result set")
    void materializedStatementAfterAnAbandonedStream() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = fixture(connection)) {
            // One row of a million, then close: the engine still has rows to produce.
            try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000000)")) {
                assertTrue(rs.next());
            }

            try (ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
                assertTrue(rs.next());
            }
            try (ResultSet rs = statement.executeQuery("DESCRIBE TABLE " + DB + ".d")) {
                assertTrue(rs.next());
                assertEquals("x", rs.getString("name"));
            }

            // And the other order, since the engine's mode is per connection and both routes
            // leave state behind.
            try (ResultSet rs = statement.executeQuery("EXISTS TABLE " + DB + ".d")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(1000000)")) {
                assertTrue(rs.next());
            }
            try (ResultSet rs = statement.executeQuery("SHOW TABLES FROM " + DB)) {
                assertTrue(rs.next());
                assertEquals("d", rs.getString(1));
            }
        }
    }

    /**
     * A failed open on the materialized route leaks nothing, over enough repetitions to see it.
     *
     * <p>The engine writes the exported Arrow stream into a struct the handle owns, and it does
     * that before it can report whether the call succeeded -- so a failure partway is the one
     * path where the handle can be destroyed holding something that still needs releasing.
     * {@code StreamHandle::closeNow()} therefore releases on the callback being set rather than
     * on a flag the success path assigns.
     *
     * <p>ASan cannot run against the released engine (findings §8), so this asserts on the
     * handle counters and on RSS not growing across a thousand failures. A syntax error is
     * enough to make {@code chdb_query_arrow_n} fail after the handle exists.
     */
    @Test
    @DisplayName("a failed materialized open leaks no handle and no memory")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void failedMaterializedOpenLeaksNothing() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            // EXPLAIN is a materialized keyword, so this reaches chdb_query_arrow_n and fails
            // inside it rather than being refused by the driver first.
            String broken = "EXPLAIN SELECT FROM WHERE ###";

            for (int i = 0; i < 50; i++) {
                assertThrows(SQLException.class, () -> statement.executeQuery(broken));
            }
            assertEquals(
                    0,
                    ChdbNative.openHandleCount(ChdbNative.KIND_STREAM),
                    "a failed materialized open leaked a stream handle");

            long baseline = residentKb();
            for (int i = 0; i < 1000; i++) {
                assertThrows(SQLException.class, () -> statement.executeQuery(broken));
            }
            long afterFailures = residentKb();

            assertEquals(
                    0,
                    ChdbNative.openHandleCount(ChdbNative.KIND_STREAM),
                    "a failed materialized open leaked a stream handle");
            assertEquals(
                    0,
                    ChdbNative.openHandleCount(ChdbNative.KIND_RESULT),
                    "a failed materialized open leaked a result handle");

            // A leaked ArrowArrayStream from the engine's Arrow converter is not a few bytes;
            // a thousand of them would be plainly visible. The allowance is for allocator and
            // JIT noise, not for a per-failure leak.
            long growthKb = afterFailures - baseline;
            assertTrue(
                    growthKb < 64 * 1024,
                    () -> "RSS grew " + growthKb + " KB over 1000 failed materialized opens");

            // And the connection still works afterwards.
            try (ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
                assertTrue(rs.next());
            }
        }
    }

    /** Resident set size in KB, from ps. Coarse, but enough to tell a leak from noise. */
    private static long residentKb() throws Exception {
        long pid = ProcessHandle.current().pid();
        Process process =
                new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
                        .redirectErrorStream(true)
                        .start();
        try (java.io.BufferedReader reader =
                new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        }
    }

    /**
     * A nested block comment must not push a streamable SELECT onto the materialized route.
     *
     * <p>ClickHouse nests block comments, and a scan that stopped at the first {@code *}{@code /}
     * read the text after the inner close as the keyword. With the classifier reporting the
     * statement {@code READ_ONLY}, that routed it to {@code chdb_query_arrow_n}, which buffers
     * the whole result: measured at +496 MB of RSS for this query against +2 MB behind a
     * non-nested comment. So this asserts the memory, not just the rows -- the rows were always
     * right.
     */
    @Test
    @DisplayName("a SELECT behind a nested block comment still streams")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nestedCommentDoesNotForceMaterialization() throws Exception {
        String body = "SELECT number, repeat('x', 100) AS pad FROM numbers(4000000)";
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            // Warm up, so the measurement is not the engine's first-query setup.
            drain(statement, "SELECT number FROM numbers(1000)");

            for (String prefix :
                    new String[] {
                        "/* /* */ */ ",
                        "/* a /* b */ c */ ",
                        "/*/**/*/ ",
                        "-- banner\n/* /* */ */ "
                    }) {
                long baseline = residentKb();
                assertEquals(4_000_000L, drain(statement, prefix + body), prefix);
                long growthKb = residentKb() - baseline;
                // The streamed path grew by kilobytes on every shape measured; the materialized
                // one by ~500 MB. Anything under 128 MB can only be the streaming route.
                assertTrue(
                        growthKb < 128 * 1024,
                        () -> "RSS grew " + growthKb + " KB reading a 4M-row result behind "
                                + prefix.replace("\n", "\\n")
                                + "-- it was materialized rather than streamed");
            }
        }
    }

    private static long drain(Statement statement, String sql) throws SQLException {
        long rows = 0;
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows++;
            }
        }
        return rows;
    }

    /**
     * Cancelling and closing concurrently, hard, on both routes.
     *
     * <p>{@code streamCancel} and the fetch path both read {@code ConnHandle::conn} and now take
     * the connection's lock to do it, inside the stream's -- so this is the shape that would
     * deadlock if that order were ever inverted, and the {@link Timeout} is what turns a
     * deadlock into a failure instead of a hung build. It is also the shape that would
     * segfault on the old code, which tested {@code conn} for null and then dereferenced it
     * with no lock holding the connection open in between.
     *
     * <p>A race is not provable by running it, so this is a smoke test, not a proof: what it
     * demonstrates is that the new lock order does not deadlock and that the handle counters
     * come back to zero after several hundred interleavings.
     */
    @Test
    @DisplayName("concurrent cancel and close on both routes neither deadlocks nor leaks")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void concurrentCancelAndClose() throws Exception {
        String[] statements = {
            // Streamed: long enough that a cancel lands mid-flight.
            "SELECT number, sipHash64(number) FROM numbers(100000000)",
            // Materialized: the route where cancel only marks the flag.
            "EXPLAIN QUERY TREE SELECT 1",
            "SHOW DATABASES",
        };

        for (int round = 0; round < 200; round++) {
            String sql = statements[round % statements.length];
            Connection connection = openMemory();
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(sql);
            assertTrue(rs.next() || true);

            CountDownLatch go = new CountDownLatch(1);
            // One thread cancels, one closes the connection underneath it. Whichever wins,
            // neither may hang and neither may take the JVM down.
            Thread canceller =
                    new Thread(
                            () -> {
                                try {
                                    go.await(30, TimeUnit.SECONDS);
                                    statement.cancel();
                                } catch (Exception ignored) {
                                    // Cancel is best-effort; the assertions are below.
                                }
                            },
                            "chdb-it-cancel-" + round);
            Thread closer =
                    new Thread(
                            () -> {
                                try {
                                    go.await(30, TimeUnit.SECONDS);
                                    connection.close();
                                } catch (Exception ignored) {
                                    // Closing a connection with a query in flight is allowed to
                                    // fail; it must not hang or crash.
                                }
                            },
                            "chdb-it-close-" + round);
            canceller.start();
            closer.start();
            go.countDown();
            canceller.join(TimeUnit.SECONDS.toMillis(30));
            closer.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(canceller.isAlive(), "the cancelling thread did not finish");
            assertFalse(closer.isAlive(), "the closing thread did not finish");

            try {
                rs.close();
            } catch (SQLException ignored) {
                // The connection may already be gone underneath it.
            }
            try {
                statement.close();
            } catch (SQLException ignored) {
                // As above.
            }
            connection.close();
        }

        // NativeTestBase asserts the counters after the test; this makes the round count part
        // of the failure message if they are not zero.
        assertEquals(
                0, ChdbNative.openHandleCount(ChdbNative.KIND_STREAM), "leaked a stream handle");
        assertEquals(
                0,
                ChdbNative.openHandleCount(ChdbNative.KIND_CONNECTION),
                "leaked a connection handle");
    }

    @Test
    @DisplayName("a materialized result set closes cleanly half-read")
    void abandonedMaterializedResultSetReleasesItsHandle() throws Exception {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("EXPLAIN QUERY TREE SELECT 1")) {
                assertTrue(rs.next());
                // Left half-read on purpose. assertNoLeakedHandles() in NativeTestBase is the
                // assertion: closing the result set has to release the exported Arrow stream
                // and its batch, not only the ones that were drained.
            }
            // The connection's statement slot has to come back too, or this second statement
            // would block.
            try (ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
                assertTrue(rs.next());
            }
        }
    }

    private static List<String> columnNames(ResultSetMetaData meta) throws SQLException {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnName(i));
        }
        return names;
    }
}
