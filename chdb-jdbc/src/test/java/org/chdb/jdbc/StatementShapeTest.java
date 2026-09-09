package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The keyword scan has to pick one of three routes, and getting it wrong sends a statement to
 * an entry point that refuses it -- which is what issue #12 was -- so all three are covered in
 * both directions.
 *
 * <p>The pinned v26.7.2-rc.2 baseline does export chdb_classify_query_n, so the scan is no
 * longer the only thing deciding. It is still what decides which of the two Arrow doors a
 * result set goes through, because the classifier does not distinguish them, as well as
 * deciding both halves whenever the classifier is absent or refuses the text -- hence the cases
 * below that pass a classifier answer alongside the SQL.
 */
class StatementShapeTest {

    @ParameterizedTest
    @DisplayName("statements the engine will stream")
    @ValueSource(
            strings = {
                "SELECT 1",
                "select 1",
                "  \n\t SELECT 1",
                "WITH x AS (SELECT 1) SELECT * FROM x",
                "(SELECT 1) UNION ALL (SELECT 2)",
                "VALUES (1), (2)",
                "TABLE t",
                "-- a banner\nSELECT 1",
                "/* a hint */ SELECT 1",
                "/* multi\nline */\n  SELECT 1",
            })
    void streamedRoute(String sql) {
        assertEquals(StatementShape.Route.STREAMED_RESULT_SET, StatementShape.route(null, sql), sql);
    }

    /**
     * The whole of issue #12: every one of these was measured failing on the streaming door of
     * the pinned v26.7.0 engine with {@code Streaming query is not supported}, and succeeding
     * on {@code chdb_query_arrow_n}. Routing them anywhere else is the bug.
     */
    @ParameterizedTest
    @DisplayName("statements with a result set the engine refuses to stream")
    @ValueSource(
            strings = {
                "SHOW TABLES",
                "SHOW DATABASES",
                "SHOW CREATE TABLE t",
                "SHOW COLUMNS FROM t",
                "SHOW SETTINGS LIKE 'max_th%'",
                "DESCRIBE TABLE t",
                "describe table t",
                "DESC t",
                "DESCRIBE (SELECT 1)",
                "EXPLAIN SELECT 1",
                "EXPLAIN AST SELECT 1",
                "EXPLAIN SYNTAX SELECT 1",
                "EXPLAIN PLAN SELECT 1",
                "EXPLAIN PIPELINE SELECT 1",
                "EXPLAIN ESTIMATE SELECT 1",
                "EXPLAIN QUERY TREE SELECT 1",
                "EXISTS TABLE t",
                "EXISTS DATABASE d",
                "CHECK TABLE t",
                "-- a banner\nSHOW TABLES",
                "/* a hint */ DESCRIBE TABLE t",
            })
    void materializedRoute(String sql) {
        assertEquals(
                StatementShape.Route.MATERIALIZED_RESULT_SET, StatementShape.route(null, sql), sql);
    }

    @ParameterizedTest
    @DisplayName("statements with no result set take neither Arrow route")
    @ValueSource(
            strings = {
                "INSERT INTO t VALUES (1)",
                "INSERT INTO t SELECT * FROM u",
                "CREATE TABLE t (x UInt8) ENGINE = Memory",
                "DROP TABLE t",
                "ALTER TABLE t ADD COLUMN y UInt8",
                "TRUNCATE TABLE t",
                "RENAME TABLE a TO b",
                "OPTIMIZE TABLE t",
                "USE other",
                "SET max_threads = 4",
                "ATTACH TABLE t",
                "DETACH TABLE t",
                "SYSTEM FLUSH LOGS",
                "KILL QUERY WHERE 1",
                "/* a hint */ insert into t values (1)",
            })
    void noResultSetRoute(String sql) {
        assertEquals(StatementShape.Route.NO_RESULT_SET, StatementShape.route(null, sql), sql);
    }

    @Test
    @DisplayName("the engine classifier wins on whether there is a result set")
    void classifierIsAuthoritative() {
        // Classifier says READ_ONLY for text the keyword scan would call a write. It settles
        // that there is a result set; the keyword scan still picks the door, and a keyword that
        // is not in the materialized set takes the streaming one -- which for this synthetic
        // case is also the safe answer, because the streaming door refuses an INSERT before
        // executing it while the materialized door would write the row and *then* report
        // "Missing result header for Arrow output".
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET,
                StatementShape.route(
                        new int[] {StatementShape.CLASS_READ_ONLY, 1, 0}, "INSERT INTO t VALUES (1)"));
        // A READ_ONLY statement that is a SELECT still streams.
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_READ_ONLY, 1, 0}, "SELECT 1"));
        // READ_ONLY plus a keyword known not to stream: the materialized route.
        assertEquals(
                StatementShape.Route.MATERIALIZED_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_READ_ONLY, 1, 0}, "SHOW TABLES"));
        // And the other way round: a classified write never reaches either Arrow route.
        assertEquals(
                StatementShape.Route.NO_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_MUTATING, 1, 0}, "SELECT 1"));
        assertEquals(
                StatementShape.Route.NO_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_CONTROL, 1, 0}, "SHOW TABLES"));
    }

    @Test
    @DisplayName("UNKNOWN from the classifier falls through to the keyword scan")
    void unknownFallsThrough() {
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_UNKNOWN, 0, 0}, "SELECT 1"));
        assertEquals(
                StatementShape.Route.MATERIALIZED_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_UNKNOWN, 0, 0}, "SHOW TABLES"));
        assertEquals(
                StatementShape.Route.NO_RESULT_SET,
                StatementShape.route(new int[] {StatementShape.CLASS_UNKNOWN, 0, 0}, "DROP TABLE t"));
    }

    @Test
    @DisplayName("a null classifier result means the engine has no classifier")
    void nullAnalysisFallsThrough() {
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET, StatementShape.route(null, "SELECT 1"));
        assertEquals(
                StatementShape.Route.NO_RESULT_SET,
                StatementShape.route(null, "INSERT INTO t VALUES (1)"));
    }

    /**
     * The reason issue #12 is fixed with a third keyword state rather than by retrying a
     * refused stream open on the materialized route.
     *
     * <p>{@code WITH q AS (...) INSERT INTO t SELECT ...} parses, writes, and has a leading
     * keyword the scan calls a result set -- and on v26.7.0 the streaming door refuses it with
     * exactly the text a {@code SHOW} gets. So "the leading keyword introduces a result set"
     * cannot stand in for "this statement cannot have written", which is what any retry would
     * need to be safe. It is routed to the streaming door, refused there, and reported.
     */
    @Test
    @DisplayName("a write behind a result-set keyword is not routed to the materialized door")
    void writeBehindResultSetKeywordIsNotMaterialized() {
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET,
                StatementShape.route(null, "WITH q AS (SELECT 2 AS x) INSERT INTO t SELECT x FROM q"));
    }

    @Test
    @DisplayName("leadingKeyword skips comments and reports the keyword upper-cased")
    void leadingKeyword() {
        assertEquals("SELECT", StatementShape.leadingKeyword("  -- hi\n select 1"));
        assertEquals("INSERT", StatementShape.leadingKeyword("/* x */ insert into t values (1)"));
        assertNull(StatementShape.leadingKeyword("   "));
        assertNull(StatementShape.leadingKeyword(null));
        assertNull(StatementShape.leadingKeyword("/* never closed"));
    }

    /**
     * Block comments nest in ClickHouse, and each of these was checked against the engine's own
     * parser on v26.7.2-rc.2: where the scan reads a keyword the engine produces a result set,
     * and where it reads none the engine either swallows the statement into the still-open
     * comment (accepted, no result set) or rejects it outright. The two agree on all nine.
     *
     * <p>Before the depth counting, the first three read {@code null}, {@code STILL} and {@code
     * null}, which with a {@code READ_ONLY} classifier answer sent a streamable {@code SELECT}
     * down the materialized route -- measured at +496 MB of RSS for a four-million-row result.
     */
    @Test
    @DisplayName("block comments nest, and the scan counts depth")
    void nestedBlockComments() {
        // Balanced: the keyword is behind the outermost close.
        assertEquals("SELECT", StatementShape.leadingKeyword("/* /* */ */ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("/* a /* b */ c */ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("/*/**/*/ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("/* a /* b /* c */ d */ e */ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("/* /* /* */ */ */ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("/* one */ /* /* two */ */ SELECT 1"));
        assertEquals("SELECT", StatementShape.leadingKeyword("-- banner\n/* /* */ */ SELECT 1"));

        // Unbalanced: still inside a comment at end of input, so there is no keyword. The
        // engine agrees -- it accepts these and produces no result set, having swallowed the
        // SELECT into the comment.
        assertNull(StatementShape.leadingKeyword("/* /* */ SELECT 1"));
        assertNull(StatementShape.leadingKeyword("/* /* /* */ */ SELECT 1"));
        assertNull(StatementShape.leadingKeyword("/* never closed SELECT 1"));

        // A stray close is not a comment at all; the engine rejects these with a syntax error.
        assertNull(StatementShape.leadingKeyword("*/ SELECT 1"));
        assertNull(StatementShape.leadingKeyword("/* a */ */ SELECT 1"));

        // And the routing consequence, which is the point of the above.
        assertEquals(
                StatementShape.Route.STREAMED_RESULT_SET,
                StatementShape.route(null, "/* /* */ */ SELECT 1"));
        assertEquals(
                StatementShape.Route.MATERIALIZED_RESULT_SET,
                StatementShape.route(null, "/* /* */ */ SHOW TABLES"));
    }

    /**
     * A prefix the scan cannot read errs toward streaming when the classifier has said there is
     * a result set, and toward no-result-set when nothing has.
     *
     * <p>Not arbitrary: the materialized door executes what it is given before checking that
     * there is a result header to export, so it is the one guess that can write. The streaming
     * door refuses anything that is not a SELECT pipeline before executing it, and its mistake
     * costs an error rather than an unbounded buffer.
     */
    @Test
    @DisplayName("an unreadable prefix errs toward streaming, not toward materializing")
    void unreadablePrefixErrsTowardStreaming() {
        for (String sql :
                new String[] {
                    "/* /* */ SELECT 1", "/* never closed", "*/ SELECT 1", "   ", "42 + 1"
                }) {
            assertEquals(
                    StatementShape.Route.STREAMED_RESULT_SET,
                    StatementShape.route(new int[] {StatementShape.CLASS_READ_ONLY, 1, 0}, sql),
                    sql);
            assertEquals(StatementShape.Route.NO_RESULT_SET, StatementShape.route(null, sql), sql);
        }
    }
}
