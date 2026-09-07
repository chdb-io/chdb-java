package org.chdb.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rewrites JDBC {@code ?} placeholders into chDB server-side named parameters.
 *
 * <p>{@code SELECT * FROM t WHERE a = ? AND b > ?} becomes {@code SELECT * FROM t WHERE a =
 * {p1:String} AND b > {p2:String}}, and the values travel separately through {@code
 * chdb_query_with_params_n}. No value is ever spliced into SQL text (work plan section 5.8),
 * so there is no injection surface to get right -- the engine binds the values after parsing.
 *
 * <h2>Why the SQL is rendered per execution</h2>
 * Parameters are declared {@code :String}, and the engine converts from text in the
 * surrounding expression -- which keeps the driver out of the business of guessing the type of
 * the column a value is being compared against, something it cannot see.
 *
 * <p>The exception is NULL. The engine's escaped-text reader only treats {@code \N} as NULL
 * when the placeholder's declared type is nullable; under {@code :String} it would read as the
 * single character {@code N}. Which parameters are NULL is not known until the caller has
 * finished calling setters, so the placeholder types are chosen at execution time and the
 * statement text is assembled then. {@link #parse} therefore keeps the literal SQL between
 * placeholders and {@link #render} joins it back together.
 *
 * <h2>What counts as a placeholder</h2>
 * Only a {@code ?} in ordinary SQL text. A {@code ?} inside any of these is data, not a
 * placeholder, and is left exactly as written:
 *
 * <ul>
 *   <li>{@code 'single-quoted strings'}, including {@code ''} and {@code \'} escapes
 *   <li>{@code "double-quoted"} and {@code `backtick-quoted`} identifiers
 *   <li>{@code -- line comments} and {@code /* block comments *}{@code /}
 *   <li>{@code $$dollar-quoted$$} and {@code $tag$...$tag$} strings
 * </ul>
 *
 * <p>Getting this wrong in either direction is a bug with teeth: treating a {@code ?} in a
 * string literal as a placeholder corrupts the query, and missing a real placeholder leaves
 * a literal {@code ?} for the engine to reject.
 */
final class SqlParameterLexer {

    /**
     * The literal SQL around the placeholders: {@code segments.size()} is always
     * {@code parameterNames.size() + 1}, so segment i precedes parameter i.
     */
    private final List<String> segments;

    private final List<String> parameterNames;

    private SqlParameterLexer(List<String> segments, List<String> parameterNames) {
        this.segments = Collections.unmodifiableList(segments);
        this.parameterNames = Collections.unmodifiableList(parameterNames);
    }

    /** Parameter names in placeholder order; index 0 is JDBC parameter 1. */
    List<String> parameterNames() {
        return parameterNames;
    }

    int parameterCount() {
        return parameterNames.size();
    }

    /**
     * Assembles the statement, declaring each placeholder with the given ClickHouse type.
     *
     * @param types one type name per parameter, in order; {@code "String"} for an ordinary
     *     value and {@code "Nullable(String)"} for one bound to SQL NULL
     */
    String render(List<String> types) {
        if (types.size() != parameterNames.size()) {
            throw new IllegalArgumentException(
                    "expected " + parameterNames.size() + " parameter types, got " + types.size());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parameterNames.size(); i++) {
            out.append(segments.get(i));
            out.append('{').append(parameterNames.get(i)).append(':').append(types.get(i)).append('}');
        }
        out.append(segments.get(segments.size() - 1));
        return out.toString();
    }

    /**
     * The statement with every placeholder declared {@code :String}.
     *
     * <p>What a caller sees for a statement with no NULLs, and what the tests pin.
     */
    String rewritten() {
        List<String> types = new ArrayList<>(parameterNames.size());
        for (int i = 0; i < parameterNames.size(); i++) {
            types.add("String");
        }
        return render(types);
    }

    /**
     * Scans {@code sql} and rewrites its placeholders.
     *
     * @throws SQLException if the statement ends inside an unterminated literal or comment,
     *     which would otherwise silently hide a placeholder
     */
    static SqlParameterLexer parse(String sql) throws SQLException {
        if (sql == null) {
            throw new SQLException("SQL must not be null", "22023");
        }

        StringBuilder out = new StringBuilder(sql.length() + 16);
        List<String> segments = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int length = sql.length();
        int i = 0;

        while (i < length) {
            char c = sql.charAt(i);

            if (c == '\'') {
                i = copyQuoted(sql, i, '\'', out, true);
                continue;
            }
            if (c == '"') {
                i = copyQuoted(sql, i, '"', out, true);
                continue;
            }
            if (c == '`') {
                i = copyQuoted(sql, i, '`', out, true);
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = copyLineComment(sql, i, out);
                continue;
            }
            if (c == '#' && i + 1 < length && sql.charAt(i + 1) == '!') {
                // ClickHouse also accepts #! as a line comment.
                i = copyLineComment(sql, i, out);
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i = copyBlockComment(sql, i, out);
                continue;
            }
            if (c == '$') {
                int after = copyDollarQuoted(sql, i, out);
                if (after > i) {
                    i = after;
                    continue;
                }
                // Not a dollar-quoted string: a lone $ is ordinary text.
            }
            if (c == '?') {
                // "??" is not an operator in ClickHouse, so there is no escape form to honour
                // here; every ? outside a literal or comment is a placeholder.
                names.add("p" + (names.size() + 1));
                segments.add(out.toString());
                out.setLength(0);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }

        segments.add(out.toString());
        return new SqlParameterLexer(segments, names);
    }

    /**
     * Copies a quoted run, including both delimiters.
     *
     * @param backslashEscapes whether a backslash escapes the next character; true for
     *     ClickHouse, which accepts both {@code ''} doubling and {@code \'}
     * @return the index just past the closing delimiter
     */
    private static int copyQuoted(
            String sql, int start, char delimiter, StringBuilder out, boolean backslashEscapes)
            throws SQLException {
        out.append(sql.charAt(start));
        int i = start + 1;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < length) {
                out.append(c).append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == delimiter) {
                // A doubled delimiter is an escaped delimiter, not the end of the run.
                if (i + 1 < length && sql.charAt(i + 1) == delimiter) {
                    out.append(c).append(c);
                    i += 2;
                    continue;
                }
                out.append(c);
                return i + 1;
            }
            out.append(c);
            i++;
        }
        throw new SQLException(
                "Unterminated " + describe(delimiter) + " starting at offset " + start
                        + ". A statement that ends inside a literal would hide a ? placeholder, so the"
                        + " driver refuses it rather than guessing where it should have closed.",
                "42601");
    }

    private static String describe(char delimiter) {
        switch (delimiter) {
            case '\'':
                return "string literal";
            case '"':
                return "double-quoted identifier";
            case '`':
                return "backtick-quoted identifier";
            default:
                return "quoted run";
        }
    }

    private static int copyLineComment(String sql, int start, StringBuilder out) {
        int i = start;
        int length = sql.length();
        while (i < length && sql.charAt(i) != '\n') {
            out.append(sql.charAt(i));
            i++;
        }
        // A comment running to end of input is fine; there is nothing left to hide.
        return i;
    }

    private static int copyBlockComment(String sql, int start, StringBuilder out) throws SQLException {
        out.append("/*");
        int i = start + 2;
        int length = sql.length();
        // SQL block comments do not nest in ClickHouse, so the first */ closes it.
        while (i < length) {
            if (sql.charAt(i) == '*' && i + 1 < length && sql.charAt(i + 1) == '/') {
                out.append("*/");
                return i + 2;
            }
            out.append(sql.charAt(i));
            i++;
        }
        throw new SQLException(
                "Unterminated block comment starting at offset " + start
                        + ". Everything after it would be treated as commented out, including any ?"
                        + " placeholder, so the driver refuses the statement.",
                "42601");
    }

    /**
     * Copies a {@code $$...$$} or {@code $tag$...$tag$} string if one starts here.
     *
     * @return the index just past the closing tag, or {@code start} if this {@code $} does not
     *     begin a dollar-quoted string
     */
    private static int copyDollarQuoted(String sql, int start, StringBuilder out) throws SQLException {
        int length = sql.length();
        int i = start + 1;
        // A tag is an optional identifier between the two dollars.
        while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i >= length || sql.charAt(i) != '$') {
            return start;
        }
        String tag = sql.substring(start, i + 1);
        int bodyStart = i + 1;
        int close = sql.indexOf(tag, bodyStart);
        if (close < 0) {
            throw new SQLException(
                    "Unterminated dollar-quoted string starting at offset " + start
                            + " (opening tag " + tag + ").",
                    "42601");
        }
        out.append(sql, start, close + tag.length());
        return close + tag.length();
    }
}
