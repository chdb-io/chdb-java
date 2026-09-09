package org.chdb.jdbc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a statement produces a result set to stream.
 *
 * <p>The driver has to know before it executes: {@code chdb_stream_query_arrow} only accepts
 * statements with a result schema, and DDL/DML has to go through {@code chdb_query_n} instead.
 *
 * <p>Two ways to answer, best first:
 *
 * <ol>
 *   <li>{@code chdb_classify_query_n}, which parses the SQL with the engine's own parser and
 *       settings and executes nothing. Authoritative, and available from engine v26.7.2-rc.2 --
 *       the pinned baseline, so this is the path taken in practice.
 *   <li>the leading-keyword scan below, for an engine that does not export the classifier.
 *       Nothing older than the baseline is supported, but the loader can be pointed at another
 *       {@code libchdb} of the same version, so the fallback stays.
 * </ol>
 *
 * <h2>Why a wrong guess is not retried</h2>
 * If the keyword scan says "result set" and the engine disagrees, the caller gets an error.
 * The driver does not then re-run the statement without streaming, because it cannot know
 * whether the first attempt had already applied a write. Re-running an {@code INSERT} that
 * partially succeeded would duplicate rows -- far worse than an error naming the statement.
 */
final class StatementShape {

    /**
     * Leading keywords of statements that produce a result set, matching the engine's
     * CHDB_QUERY_READ_ONLY class plus the ClickHouse-specific forms.
     */
    private static final Set<String> RESULT_SET_KEYWORDS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "SELECT",
                                    // WITH introduces a CTE for a following SELECT.
                                    "WITH",
                                    "SHOW",
                                    "DESCRIBE",
                                    "DESC",
                                    "EXPLAIN",
                                    "EXISTS",
                                    "CHECK",
                                    "VALUES",
                                    // ClickHouse accepts "TABLE t" as shorthand for SELECT * FROM t.
                                    "TABLE")));

    /** Query classes from {@code chdb_query_class}. */
    static final int CLASS_READ_ONLY = 0;
    static final int CLASS_MUTATING = 1;
    static final int CLASS_MUTATING_GLOBAL = 2;
    static final int CLASS_CONTROL = 3;
    static final int CLASS_UNKNOWN = 4;

    private StatementShape() {
    }

    /**
     * Decides from the engine classifier's answer, or from the SQL if there is none.
     *
     * @param analysis {@code ChdbNative.classifyQuery} output, or null when unavailable
     */
    static boolean producesResultSet(int[] analysis, String sql) {
        if (analysis != null && analysis.length >= 1) {
            int queryClass = analysis[0];
            if (queryClass == CLASS_READ_ONLY) {
                return true;
            }
            if (queryClass != CLASS_UNKNOWN) {
                return false;
            }
            // UNKNOWN means the engine's parser could not classify it. Falling through to the
            // keyword scan is better than refusing: the statement may still be something this
            // engine version executes but does not classify.
        }
        return startsWithResultSetKeyword(sql);
    }

    /** Whether the first keyword of {@code sql} introduces a result set. */
    static boolean startsWithResultSetKeyword(String sql) {
        String keyword = leadingKeyword(sql);
        return keyword != null && RESULT_SET_KEYWORDS.contains(keyword);
    }

    /**
     * The first SQL keyword, upper-cased, skipping whitespace and leading comments.
     *
     * <p>Comments have to be skipped, not just whitespace: a query prefixed by a {@code --}
     * banner or a {@code /* ... *}{@code /} hint is common, and reading its first word as the
     * keyword would misclassify every such statement.
     */
    static String leadingKeyword(String sql) {
        if (sql == null) {
            return null;
        }
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                // A parenthesized SELECT, as in "(SELECT 1) UNION ALL (SELECT 2)".
                i++;
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) {
                    return null;
                }
                i = close + 2;
                continue;
            }
            break;
        }

        int start = i;
        while (i < length && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i == start) {
            return null;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }
}
