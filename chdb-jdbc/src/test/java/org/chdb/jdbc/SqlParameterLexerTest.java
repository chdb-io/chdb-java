package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The lexer decides which {@code ?} is a parameter and which is data. Both mistakes are
 * serious -- rewriting a {@code ?} inside a literal corrupts the query, missing a real one
 * leaves the engine a placeholder it will reject -- so this covers each context that can hide
 * a question mark.
 */
class SqlParameterLexerTest {

    @Test
    @DisplayName("a plain placeholder becomes a named parameter reference")
    void rewritesPlaceholder() throws SQLException {
        SqlParameterLexer lexer = SqlParameterLexer.parse("SELECT * FROM t WHERE a = ?");
        assertEquals("SELECT * FROM t WHERE a = {p1:String}", lexer.rewritten());
        assertEquals(List.of("p1"), lexer.parameterNames());
    }

    @Test
    @DisplayName("placeholders are numbered in order")
    void numbersInOrder() throws SQLException {
        SqlParameterLexer lexer = SqlParameterLexer.parse("SELECT ?, ?, ?");
        assertEquals("SELECT {p1:String}, {p2:String}, {p3:String}", lexer.rewritten());
        assertEquals(3, lexer.parameterCount());
    }

    @Test
    @DisplayName("no placeholders leaves the SQL untouched")
    void leavesPlainSqlAlone() throws SQLException {
        String sql = "SELECT count() FROM system.tables";
        assertEquals(sql, SqlParameterLexer.parse(sql).rewritten());
        assertEquals(0, SqlParameterLexer.parse(sql).parameterCount());
    }

    @ParameterizedTest
    @DisplayName("a question mark inside a quoted or commented run is data")
    @ValueSource(
            strings = {
                "SELECT 'a?b'",
                "SELECT 'it''s a ? really'",
                "SELECT 'escaped \\' and ?'",
                "SELECT \"col?umn\" FROM t",
                "SELECT `col?umn` FROM t",
                "SELECT 1 -- trailing ? comment",
                "SELECT 1 /* inline ? comment */",
                "SELECT 1 /* multi\nline ? comment */",
                "SELECT $$dollar ? quoted$$",
                "SELECT $tag$tagged ? body$tag$",
                "SELECT 1 #! hash bang ? comment",
            })
    void treatsQuotedQuestionMarkAsData(String sql) throws SQLException {
        SqlParameterLexer lexer = SqlParameterLexer.parse(sql);
        assertEquals(0, lexer.parameterCount(), "should have found no placeholder in: " + sql);
        assertEquals(sql, lexer.rewritten(), "should have left the SQL unchanged: " + sql);
    }

    @Test
    @DisplayName("a real placeholder next to a quoted one is still found")
    void findsPlaceholderBesideQuotedQuestionMark() throws SQLException {
        SqlParameterLexer lexer =
                SqlParameterLexer.parse("SELECT 'a?b' AS lit, ? AS bound -- and ? here");
        assertEquals(1, lexer.parameterCount());
        assertEquals("SELECT 'a?b' AS lit, {p1:String} AS bound -- and ? here", lexer.rewritten());
    }

    @Test
    @DisplayName("a doubled quote does not end the literal")
    void doubledQuoteContinuesLiteral() throws SQLException {
        // If '' were read as a close followed by an open, the ? between them would look like
        // ordinary SQL and be rewritten.
        SqlParameterLexer lexer = SqlParameterLexer.parse("SELECT 'a''?''b', ?");
        assertEquals(1, lexer.parameterCount());
        assertTrue(lexer.rewritten().startsWith("SELECT 'a''?''b', {p1:String}"));
    }

    @Test
    @DisplayName("an unterminated literal is refused rather than guessed at")
    void refusesUnterminatedLiteral() {
        SQLException e =
                assertThrows(SQLException.class, () -> SqlParameterLexer.parse("SELECT 'unclosed ?"));
        assertTrue(e.getMessage().contains("Unterminated string literal"), e.getMessage());
        assertEquals("42601", e.getSQLState());
    }

    @Test
    @DisplayName("an unterminated block comment is refused")
    void refusesUnterminatedBlockComment() {
        SQLException e =
                assertThrows(
                        SQLException.class, () -> SqlParameterLexer.parse("SELECT 1 /* unclosed ?"));
        assertTrue(e.getMessage().contains("Unterminated block comment"), e.getMessage());
    }

    @Test
    @DisplayName("an unterminated dollar-quoted string is refused")
    void refusesUnterminatedDollarQuote() {
        SQLException e =
                assertThrows(SQLException.class, () -> SqlParameterLexer.parse("SELECT $tag$ oops ?"));
        assertTrue(e.getMessage().contains("Unterminated dollar-quoted"), e.getMessage());
    }

    @Test
    @DisplayName("a lone dollar sign is ordinary text, not the start of a dollar quote")
    void loneDollarIsText() throws SQLException {
        SqlParameterLexer lexer = SqlParameterLexer.parse("SELECT 1 $ 2, ?");
        assertEquals(1, lexer.parameterCount());
    }

    @Test
    @DisplayName("a line comment running to end of input terminates cleanly")
    void lineCommentAtEndOfInput() throws SQLException {
        SqlParameterLexer lexer = SqlParameterLexer.parse("SELECT ? -- done");
        assertEquals(1, lexer.parameterCount());
    }

    @Test
    @DisplayName("null SQL is refused")
    void refusesNull() {
        assertThrows(SQLException.class, () -> SqlParameterLexer.parse(null));
    }
}
