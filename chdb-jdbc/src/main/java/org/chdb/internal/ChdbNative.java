package org.chdb.internal;

/**
 * Every native method of the chDB JNI shim.
 *
 * <p>Not public API. Signatures here and in {@code chdb-jni/src/chdb_jni.cpp} are one
 * contract, versioned by {@link #JNI_ABI_VERSION}; changing either side without the other
 * is a load-time failure, not a compile-time one.
 *
 * <h2>Handles</h2>
 * The {@code long} handles are registry ids minted by the shim, never pointers. An id that
 * has been closed is absent from the registry, so using one raises {@link
 * ChdbNativeException} instead of dereferencing freed memory (work plan section 5.5).
 *
 * <h2>Strings</h2>
 * Every text parameter crosses as {@code byte[]} holding UTF-8, and the shim calls the
 * length-carrying {@code _n} variants of the C API. Nothing uses {@code GetStringUTFChars},
 * whose modified UTF-8 misencodes supplementary characters and cannot express a NUL.
 *
 * <h2>Threading</h2>
 * A chDB connection runs one statement at a time. The shim serializes per handle, but the
 * JDBC layer above it is what gives callers a defined answer for concurrent use; see
 * {@code ChdbConnection}.
 */
public final class ChdbNative {

    /**
     * Must equal {@code chdb_jni::kJniAbiVersion} in the shim and {@code
     * chdb.jni.abi.version} in the poms. Verified at load time before any other call.
     */
    public static final int JNI_ABI_VERSION = 2;

    /** Handle kinds, for {@link #openHandleCount(int)}. */
    public static final int KIND_CONNECTION = 1;
    public static final int KIND_RESULT = 2;
    /**
     * 4 rather than 3: the Arrow stream kind was 3 and is gone, and reusing its number would
     * make an old shim and a new driver agree on a number while disagreeing on its meaning.
     */
    public static final int KIND_ROW_BINARY = 4;

    static {
        // Loads libchdb and then the shim, in that order, and verifies the ABI.
        //
        // The verification inside ensureLoaded() calls native methods on this very class,
        // re-entering this initializer on the same thread. JLS 12.4.2 defines that as a
        // recursive request that completes normally, so the calls resolve against the
        // just-loaded library rather than deadlocking or seeing an uninitialized class.
        NativeLibraryLoader.ensureLoaded();
    }

    private ChdbNative() {
    }

    // ---------------------------------------------------------------- identity / self-check

    /** ABI version the loaded shim was built with. */
    public static native int jniAbiVersion();

    /** {@code chdb_version()} of the loaded libchdb, e.g. {@code "26.7.3"}. */
    public static native String engineVersion();

    /** Build provenance of the shim: git commit, compiler, engine headers it compiled against. */
    public static native String shimBuildInfo();

    // ---------------------------------------------------------------- signal handling

    /**
     * One line per guarded signal describing its current disposition. Diagnostic output for
     * the signal regression tests (work plan section 5.6); the format is not a contract.
     */
    public static native String signalDispositions();

    /**
     * Opts out of chDB's process-wide signal handlers while preserving the host JVM's.
     *
     * <p>Idempotent, and called exactly once per process, from {@link
     * NativeLibraryLoader#load()}. {@code chdb_set_signal_handlers_enabled(0)} resets the
     * incumbent handlers for SIGSEGV, SIGBUS, SIGILL, SIGFPE and four others to {@code
     * SIG_DFL} as a side effect, which would leave the JVM unable to service its own
     * implicit null checks; the shim brackets the call and puts the host dispositions back.
     *
     * <p>The bracket is not atomic and cannot be: dispositions are process-wide, so between
     * chDB's reset and the shim's restore there is a window -- microseconds -- in which the
     * JVM has no crash handlers, and another thread taking a SIGSEGV in it dies immediately
     * with no {@code hs_err_pid} report, because HotSpot's crash reporter is the handler that
     * was removed. That is why this is called once rather than per connection, and why the
     * opt-out is still worth making: leaving the flag clear lets the engine install its own
     * handlers for the whole duration of every {@code chdb_connect()} instead, which is the
     * same hazard over a window three orders of magnitude wider. The residual window belongs
     * upstream; see issue #14.
     *
     * @return names of the signals whose disposition chDB changed and the shim restored,
     *     empty once upstream stops resetting host handlers
     */
    public static native String[] protectHostSignalHandlers();

    // ---------------------------------------------------------------- connection

    /**
     * {@code chdb_connect}. {@code argv} is the full argument vector including argv[0],
     * each element UTF-8.
     *
     * @return a connection handle
     * @throws ChdbNativeException if the engine refuses the connection
     */
    public static native long connect(byte[][] argv);

    /** {@code chdb_close_conn}. Idempotent: closing an already-closed handle is a no-op. */
    public static native void closeConnection(long connection);

    // ---------------------------------------------------------------- non-streaming query

    /**
     * {@code chdb_query_n}, or {@code chdb_query_with_params_n} when {@code paramNames} is
     * non-empty. Used for statements with no result set to stream -- DDL, DML, update
     * counts -- and for the classifier's CONTROL class.
     *
     * @param paramNames server-side parameter names, parallel to {@code paramValues}
     * @return a result handle, which the caller must pass to {@link #destroyResult(long)}
     * @throws ChdbNativeException carrying the engine's error text if the query failed
     */
    public static native long query(
            long connection, byte[] sql, byte[] format, byte[][] paramNames, byte[][] paramValues);

    /** Result payload bytes, or an empty array for a statement that produced none. */
    public static native byte[] resultBytes(long result);

    /** {@code chdb_result_elapsed}, in seconds. */
    public static native double resultElapsed(long result);

    /** {@code chdb_result_rows_read}. */
    public static native long resultRowsRead(long result);

    /** {@code chdb_result_rows_written} -- the update count source for DML. */
    public static native long resultRowsWritten(long result);

    /** {@code chdb_destroy_query_result}. Idempotent. */
    public static native void destroyResult(long result);

    /**
     * {@code chdb_classify_query_n}. Decides whether a statement has a result set to stream
     * without executing it, using the engine's own parser.
     *
     * <p>Optional symbol: it landed in chdb-core v26.7.2-rc.2, so the pinned v26.7.3 baseline has it,
     * so it is resolved by {@code dlsym} rather than linked. It is present on the baseline;
     * the null path is what an older engine gets, and means the caller must fall back to
     * {@code StatementShape}'s own analysis.
     *
     * @return {@code {queryClass, statementCount, flags}} where {@code queryClass} follows
     *     {@code chdb_query_class} (0 READ_ONLY, 1 MUTATING, 2 MUTATING_GLOBAL, 3 CONTROL,
     *     4 UNKNOWN), or null if this engine does not export the classifier
     */
    public static native int[] classifyQuery(long connection, byte[] sql);

    // ---------------------------------------------------------------- RowBinary streaming

    /**
     * {@code chdb_stream_query_with_params_n} with a {@code RowBinaryWithNamesAndTypes} format.
     *
     * <p>The one path results arrive on. It hands over the bytes of a RowBinary stream whose
     * header names every type as the engine declared it; nothing is decoded natively. The Arrow
     * entry points this replaced typed a column off the Arrow schema instead, which is the
     * engine's own lossy projection of its type system.
     *
     * <p>It has a parameterised variant, which those did not: a parameterised {@code SHOW} or
     * {@code DESCRIBE} has a path here where it had none before.
     *
     * @param format the ClickHouse output format, normally {@code RowBinaryWithNamesAndTypes}
     * @return a stream handle of {@link #KIND_ROW_BINARY}
     * @throws ChdbNativeException carrying the engine's error text if the statement failed
     */
    public static native long rowBinaryOpen(
            long connection, byte[] sql, byte[] format, byte[][] paramNames, byte[][] paramValues);

    /**
     * The next chunk of the stream, or null once it has ended.
     *
     * <p>Each chunk is a whole document: its own header followed by whole rows, never a row
     * split across two chunks. Measured rather than documented, so {@link RowBinaryCursor}
     * checks it instead of trusting it. The bytes are copied out of the engine's buffer before
     * it is released, so the array is the caller's to keep.
     *
     * @throws ChdbNativeException if the engine reported a mid-stream error
     */
    public static native byte[] rowBinaryFetch(long connection, long stream);

    /** {@code chdb_stream_cancel_query}. Safe on a stream that already ended. */
    public static native void rowBinaryCancel(long connection, long stream);

    /** Releases the engine's stream handle. Idempotent. */
    public static native void rowBinaryClose(long stream);

    // ---------------------------------------------------------------- diagnostics

    /**
     * Live handles of one {@code KIND_*}. Tests assert this returns to zero after every
     * normal and error path (work plan section 5.11).
     */
    public static native long openHandleCount(int kind);

    /**
     * {@code chdb_shutdown}: joins every engine thread. Every connection must be closed and
     * every result destroyed first, and the engine cannot be used again in this process.
     *
     * <p>Optional symbol, like {@link #classifyQuery(long, byte[])}: it landed in chdb-core
     * v26.7.2-rc.2, so the pinned v26.7.3 baseline has it, and it is resolved by {@code dlsym} rather
     * than linked. Not having it is not a failure -- the threads it would join are reaped by
     * process exit either way.
     *
     * <p>1 is the ordinary answer while any connection is still open: the engine declines to
     * tear itself down under a live connection rather than leaving it dangling. Callers that
     * want it to do something have to close everything first.
     *
     * @return 0 on success, 1 if a connection is still open or a thread would not stop, 2 if
     *     this engine does not export {@code chdb_shutdown}
     */
    public static native int shutdown();
}
