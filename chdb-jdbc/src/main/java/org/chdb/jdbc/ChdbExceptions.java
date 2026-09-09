package org.chdb.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.chdb.internal.ChdbNativeException;
import org.chdb.internal.UnsupportedPlatformException;

/** Turns native and engine failures into the {@link SQLException} subclasses JDBC callers expect. */
final class ChdbExceptions {

    /**
     * ClickHouse error text starts with {@code Code: <n>. DB::Exception: <message>}. The code
     * is worth extracting: it is stable across versions and is what {@link
     * SQLException#getErrorCode()} is for, so a caller can branch on
     * {@code TIMEOUT_EXCEEDED} without matching on message text.
     */
    private static final Pattern ERROR_CODE = Pattern.compile("^\\s*Code:\\s*(\\d+)[.,]");

    // ClickHouse error codes this driver maps to a specific SQLException subclass. Everything
    // else becomes a plain SQLException: guessing a category wrongly is worse than not
    // guessing, because callers branch on the type.
    private static final int SYNTAX_ERROR = 62;
    private static final int UNKNOWN_IDENTIFIER = 47;
    private static final int UNKNOWN_TABLE = 60;
    private static final int UNKNOWN_DATABASE = 81;
    private static final int UNKNOWN_FUNCTION = 46;
    private static final int TYPE_MISMATCH = 53;
    private static final int ILLEGAL_TYPE_OF_ARGUMENT = 43;
    private static final int TIMEOUT_EXCEEDED = 159;
    private static final int QUERY_WAS_CANCELLED = 394;
    private static final int MEMORY_LIMIT_EXCEEDED = 241;
    private static final int NOT_IMPLEMENTED = 48;

    private ChdbExceptions() {
    }

    /** Wraps a native failure, preserving the engine's message and error code. */
    static SQLException wrap(String context, ChdbNativeException cause) {
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        int code = errorCode(message);
        String full = context == null || context.isEmpty() ? message : context + ": " + message;

        switch (code) {
            case SYNTAX_ERROR:
            case UNKNOWN_IDENTIFIER:
            case UNKNOWN_TABLE:
            case UNKNOWN_DATABASE:
            case UNKNOWN_FUNCTION:
                return new SQLSyntaxErrorException(full, "42000", code, cause);
            case TYPE_MISMATCH:
            case ILLEGAL_TYPE_OF_ARGUMENT:
                return new SQLNonTransientException(full, "42804", code, cause);
            case TIMEOUT_EXCEEDED:
                return new SQLTimeoutException(full, "57014", code, cause);
            case QUERY_WAS_CANCELLED:
                // 57014 covers both timeout and cancel in the SQL standard; the error code
                // distinguishes them.
                return new SQLTimeoutException(full, "57014", code, cause);
            case MEMORY_LIMIT_EXCEEDED:
                // Transient: the same query may well succeed when the machine is less busy,
                // or with a higher max_memory_usage. That is what makes retry meaningful.
                return new SQLTransientException(full, "53200", code, cause);
            case NOT_IMPLEMENTED:
                return new SQLFeatureNotSupportedException(full, "0A000", code, cause);
            default:
                return new SQLException(full, null, code, cause);
        }
    }

    /** Wraps a platform-detection failure as a connection error. */
    static SQLException wrap(String context, UnsupportedPlatformException cause) {
        return new SQLNonTransientException(context + ": " + cause.getMessage(), "08001", cause);
    }

    /** The ClickHouse error code in a message, or 0 if it carries none. */
    static int errorCode(String message) {
        if (message == null) {
            return 0;
        }
        Matcher matcher = ERROR_CODE.matcher(message);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The exception for a JDBC method V1 does not implement.
     *
     * <p>Work plan section 3.4: an unimplemented method throws, and never returns a fake
     * {@code null}, {@code 0} or success. A framework that probes for a capability gets a
     * clear answer, and a caller relying on one finds out at the call rather than from wrong
     * results later.
     */
    static SQLFeatureNotSupportedException notSupported(String what) {
        return new SQLFeatureNotSupportedException(
                what + " is not supported by the chDB JDBC driver V1. See docs/unsupported.md for"
                        + " the full list and the rationale.",
                "0A000");
    }

    /** The exception for a method called on a closed object. */
    static SQLException closed(String what) {
        return new SQLNonTransientException(what + " is closed", "HY010");
    }

    /**
     * The exception for a statement that arrived while the shutdown hook was closing its
     * connection.
     *
     * <p>Refusing is the only safe answer: the engine aborts if a connection is closed while a
     * statement is starting on it, so once the hook has claimed the connection nothing may
     * reach the engine through it. Saying so is the point — the alternative is a caller that
     * sees a bare {@code NullPointerException}, a silently skipped statement, or the abort
     * itself.
     *
     * <p>{@code 08003} rather than {@code HY010}: the connection is gone, not merely misused,
     * and a caller retrying against a new connection is doing the right thing — although in
     * this case there will be no new connection, because the JVM is on its way out.
     */
    static SQLException shuttingDown() {
        return new SQLNonTransientException(
                "The JVM is shutting down and the chDB driver's shutdown hook is closing this"
                        + " Connection, so no statement can be started on it. Stop the threads"
                        + " that query chDB before returning from main, or disable the hook with"
                        + " -Dchdb.shutdownHook=false and manage teardown yourself. See"
                        + " docs/unsupported.md.",
                "08003");
    }
}
