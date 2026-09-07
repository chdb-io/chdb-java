package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The keyword scan is the fallback used on the pinned v26.7.0 engine, which does not export
 * chdb_classify_query_n. Getting it wrong sends a statement down the wrong execution path, so
 * both directions are covered.
 */
class StatementShapeTest {

    @ParameterizedTest
    @DisplayName("statements that return rows")
    @ValueSource(
            strings = {
                "SELECT 1",
                "select 1",
                "  \n\t SELECT 1",
                "WITH x AS (SELECT 1) SELECT * FROM x",
                "SHOW TABLES",
                "DESCRIBE TABLE t",
                "DESC t",
                "EXPLAIN SELECT 1",
                "EXISTS TABLE t",
                "CHECK TABLE t",
                "VALUES (1), (2)",
                "TABLE t",
                "(SELECT 1) UNION ALL (SELECT 2)",
                "-- a banner\nSELECT 1",
                "/* a hint */ SELECT 1",
                "/* multi\nline */\n  SELECT 1",
            })
    void producesResultSet(String sql) {
        assertTrue(StatementShape.startsWithResultSetKeyword(sql), sql);
    }

    @ParameterizedTest
    @DisplayName("statements that do not return rows")
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
            })
    void producesNoResultSet(String sql) {
        assertFalse(StatementShape.startsWithResultSetKeyword(sql), sql);
    }

    @Test
    @DisplayName("the engine classifier wins when it has an answer")
    void classifierIsAuthoritative() {
        // Classifier says READ_ONLY for text the keyword scan would call a write.
        assertTrue(
                StatementShape.producesResultSet(
                        new int[] {StatementShape.CLASS_READ_ONLY, 1, 0}, "INSERT INTO t VALUES (1)"));
        // And the other way round.
        assertFalse(
                StatementShape.producesResultSet(
                        new int[] {StatementShape.CLASS_MUTATING, 1, 0}, "SELECT 1"));
    }

    @Test
    @DisplayName("UNKNOWN from the classifier falls through to the keyword scan")
    void unknownFallsThrough() {
        assertTrue(
                StatementShape.producesResultSet(
                        new int[] {StatementShape.CLASS_UNKNOWN, 0, 0}, "SELECT 1"));
        assertFalse(
                StatementShape.producesResultSet(
                        new int[] {StatementShape.CLASS_UNKNOWN, 0, 0}, "DROP TABLE t"));
    }

    @Test
    @DisplayName("a null classifier result means the engine has no classifier")
    void nullAnalysisFallsThrough() {
        assertTrue(StatementShape.producesResultSet(null, "SELECT 1"));
        assertFalse(StatementShape.producesResultSet(null, "INSERT INTO t VALUES (1)"));
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
}
