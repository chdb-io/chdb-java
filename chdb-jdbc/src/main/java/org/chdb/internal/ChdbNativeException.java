package org.chdb.internal;

/**
 * An error raised inside the JNI shim or reported by the chDB engine.
 *
 * <p>Thrown by every native entry point rather than letting a C++ exception unwind across
 * the JNI boundary, which is undefined behaviour (work plan section 2.1). The JDBC layer
 * catches it and converts it into a {@link java.sql.SQLException} carrying the engine's
 * error code; callers of the public API should never see this type.
 *
 * <p>Unchecked on purpose: the native methods it comes from are package-internal, and
 * making it checked would put {@code throws} clauses on the whole internal call graph
 * without adding a recovery path anywhere.
 */
public class ChdbNativeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Constructed by the shim via {@code ThrowNew}, which needs exactly this signature. */
    public ChdbNativeException(String message) {
        super(message);
    }

    public ChdbNativeException(String message, Throwable cause) {
        super(message, cause);
    }
}
