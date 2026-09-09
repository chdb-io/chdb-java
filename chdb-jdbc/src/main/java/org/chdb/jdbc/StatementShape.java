package org.chdb.jdbc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which of the engine's three execution entry points a statement has to go through.
 *
 * <p>The driver has to know before it executes, and the engine offers three doors, not two:
 *
 * <ul>
 *   <li>{@code chdb_stream_query_arrow_n} -- a result set delivered one Arrow batch at a
 *       time. Accepts a SELECT pipeline and nothing else: {@code
 *       ClientBase::processTextAsSingleQuery} admits a statement only if it parses as an
 *       {@code ASTSelectWithUnionQuery} and otherwise raises {@code Streaming query is not
 *       supported for query: ...}.
 *   <li>{@code chdb_query_arrow_n} -- the whole result set through the same Arrow C Data
 *       Interface, materialized. Accepts everything the streaming door does plus every
 *       non-SELECT read: {@code SHOW}, {@code DESCRIBE}, {@code EXPLAIN}, {@code EXISTS},
 *       {@code CHECK}.
 *   <li>{@code chdb_query_n} -- no result set. DDL, DML, session control.
 * </ul>
 *
 * <p>Two ways to answer, best first:
 *
 * <ol>
 *   <li>{@code chdb_classify_query_n}, which parses the SQL with the engine's own parser and
 *       settings and executes nothing. Available from engine v26.7.2-rc.2 -- the pinned
 *       baseline -- so it is asked in practice. Authoritative about <em>whether</em> there is a
 *       result set, but silent on <em>which door</em> delivers it: {@code
 *       CHDB_QUERY_READ_ONLY} covers a {@code SELECT} and a {@code SHOW} alike, so it cannot
 *       tell the two Arrow entry points apart. See {@link #route(int[], String)}.
 *   <li>the leading-keyword scan below, which answers the half the classifier does not, and
 *       answers both halves for an engine that does not export it. Nothing older than the
 *       baseline is supported, but the loader can be pointed at another {@code libchdb} of the
 *       same version, so the fallback stays.
 * </ol>
 *
 * <h2>Why three keyword states rather than a retry (issue #12)</h2>
 * The keyword scan carries the stream/materialize distinction itself, so every statement is
 * executed exactly once, on the first attempt, by the door that accepts it. The alternative --
 * open a stream, and on {@code Streaming query is not supported} re-run on the materialized
 * door -- was rejected, because a retry needs a <em>proof</em> that the first attempt wrote
 * nothing, and no proof is available:
 *
 * <ul>
 *   <li>A keyword whitelist is not one. {@code WITH q AS (SELECT 2 AS x) INSERT INTO t SELECT
 *       x FROM q} parses, writes, and has leading keyword {@code WITH} -- so "the leading
 *       keyword introduces a result set" does not imply "this statement cannot have written".
 *       Measured on v26.7.0: that statement is refused by the streaming door with exactly the
 *       same {@code Streaming query is not supported} text a {@code SHOW} gets.
 *   <li>The engine's error text is not one either. The refusal is raised before {@code
 *       processParsedSingleQuery}, so on v26.7.0 nothing had run -- but keying a
 *       re-execution decision on a message prefix means that the day the engine reworks that
 *       path, the driver silently starts duplicating writes. An error is recoverable; a
 *       duplicated {@code INSERT} is not.
 * </ul>
 *
 * <p>So {@code StatementShape}'s original rule stands unchanged: a statement the engine
 * refuses is reported, never re-run. What changed is that the refusal no longer happens for
 * the statements in {@link #MATERIALIZED_RESULT_SET_KEYWORDS}, because they are never sent to
 * the streaming door in the first place.
 *
 * <p>The residual gap is a write whose leading keyword is a result-set keyword -- the {@code
 * WITH ... INSERT} form above. It fails with the engine's own message and is not retried,
 * which is the same answer this class gave before, and it resolves on its own once the
 * classifier is available: {@code chdb_classify_query_n} reports it as MUTATING.
 */
final class StatementShape {

    /** Which engine entry point a statement has to go through. */
    enum Route {
        /** {@code chdb_query_n}: no result set to deliver. */
        NO_RESULT_SET,
        /** {@code chdb_stream_query_arrow_n}: a result set, one batch at a time. */
        STREAMED_RESULT_SET,
        /** {@code chdb_query_arrow_n}: a result set the engine will not stream. */
        MATERIALIZED_RESULT_SET,
    }

    /**
     * Leading keywords whose statements produce a result set the engine will stream.
     *
     * <p>All of these parse as an {@code ASTSelectWithUnionQuery}, which is the engine's
     * admission test for the streaming door. {@code TABLE t} and {@code VALUES (...)} are in
     * here because v26.7.0 parses both as an ordinary SELECT expression rather than as a
     * statement of their own -- {@code TABLE pd} comes back as {@code Unknown expression
     * identifier `TABLE` in scope SELECT `TABLE` AS pd} -- so the streaming door accepts them
     * and rejects them on semantics, which is the engine's answer to give, not the driver's.
     */
    private static final Set<String> STREAMABLE_RESULT_SET_KEYWORDS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "SELECT",
                                    // WITH introduces a CTE for a following SELECT.
                                    "WITH",
                                    "VALUES",
                                    "TABLE")));

    /**
     * Leading keywords whose statements produce a result set the engine refuses to stream.
     *
     * <p>Every one of these was measured failing on the streaming door and succeeding on
     * {@code chdb_query_arrow_n} on the pinned v26.7.0 engine (issue #12). They are all
     * metadata reads with results bounded by the schema, so materializing them costs nothing
     * the streaming path was buying.
     */
    private static final Set<String> MATERIALIZED_RESULT_SET_KEYWORDS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList("SHOW", "DESCRIBE", "DESC", "EXPLAIN", "EXISTS", "CHECK")));

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
    static Route route(int[] analysis, String sql) {
        if (analysis != null && analysis.length >= 1) {
            int queryClass = analysis[0];
            if (queryClass == CLASS_READ_ONLY) {
                // The classifier settles that there is a result set but not how to read it,
                // so the keyword scan picks the door. An unrecognized keyword lands on the
                // materialized one: it accepts everything the streaming one does, so the
                // worst case is a bounded loss of streaming rather than a failure -- and a
                // read-only statement whose keyword is not SELECT is a metadata query, which
                // is what makes that trade safe.
                return isStreamable(sql) ? Route.STREAMED_RESULT_SET : Route.MATERIALIZED_RESULT_SET;
            }
            if (queryClass != CLASS_UNKNOWN) {
                return Route.NO_RESULT_SET;
            }
            // UNKNOWN means the engine's parser could not classify it. Falling through to the
            // keyword scan is better than refusing: the statement may still be something this
            // engine version executes but does not classify.
        }

        String keyword = leadingKeyword(sql);
        if (keyword == null) {
            return Route.NO_RESULT_SET;
        }
        if (STREAMABLE_RESULT_SET_KEYWORDS.contains(keyword)) {
            return Route.STREAMED_RESULT_SET;
        }
        if (MATERIALIZED_RESULT_SET_KEYWORDS.contains(keyword)) {
            return Route.MATERIALIZED_RESULT_SET;
        }
        return Route.NO_RESULT_SET;
    }

    /** Whether the first keyword of {@code sql} introduces a result set the engine streams. */
    private static boolean isStreamable(String sql) {
        String keyword = leadingKeyword(sql);
        return keyword != null && STREAMABLE_RESULT_SET_KEYWORDS.contains(keyword);
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
