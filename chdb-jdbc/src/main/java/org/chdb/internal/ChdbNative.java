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
    public static final int JNI_ABI_VERSION = 1;

    /** Handle kinds, for {@link #openHandleCount(int)}. */
    public static final int KIND_CONNECTION = 1;
    public static final int KIND_RESULT = 2;
    public static final int KIND_STREAM = 3;

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

    /** {@code chdb_version()} of the loaded libchdb, e.g. {@code "26.7.2-rc.2"}. */
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
     * <p>Optional symbol: it landed in chdb-core v26.7.2-rc.2, which is the pinned baseline,
     * so it is resolved by {@code dlsym} rather than linked. It is present on the baseline;
     * the null path is what an older engine gets, and means the caller must fall back to
     * {@code StatementShape}'s own analysis.
     *
     * @return {@code {queryClass, statementCount, flags}} where {@code queryClass} follows
     *     {@code chdb_query_class} (0 READ_ONLY, 1 MUTATING, 2 MUTATING_GLOBAL, 3 CONTROL,
     *     4 UNKNOWN), or null if this engine does not export the classifier
     */
    public static native int[] classifyQuery(long connection, byte[] sql);

    // ---------------------------------------------------------------- arrow streaming query

    /**
     * {@code chdb_stream_query_arrow_n}, or the {@code _with_params_n} variant when {@code
     * paramNames} is non-empty.
     *
     * <p>The shim fetches the first batch eagerly, because that is what produces the Arrow
     * schema, and JDBC requires {@code getMetaData()} to answer before the first {@code
     * next()}. The batch is held as the stream's pending batch and handed out by the first
     * {@link #streamAdvance(long, long)}.
     *
     * @return a stream handle
     * @throws ChdbNativeException if the engine refuses to stream the statement
     */
    public static native long streamOpen(
            long connection,
            byte[] sql,
            byte[][] paramNames,
            byte[][] paramValues,
            boolean lowCardinalityAsDictionary,
            boolean unsupportedAsBinary,
            boolean stringAsString);

    /** Column names from the Arrow schema, in ordinal order. */
    public static native String[] streamColumnNames(long stream);

    /**
     * Arrow C Data Interface format strings, in ordinal order -- {@code "i"}, {@code "u"},
     * {@code "tsu:UTC"}, {@code "d:38,10"} and so on. The schema is stable for the lifetime
     * of the stream, so these are read once.
     */
    public static native String[] streamColumnFormats(long stream);

    /** Per-column {@code ARROW_FLAG_NULLABLE}, in ordinal order. */
    public static native boolean[] streamColumnNullable(long stream);

    /**
     * Releases the current batch and makes the next one current.
     *
     * @return the new batch's row count, or -1 at end of stream
     * @throws ChdbNativeException if the engine reported a mid-stream error
     */
    public static native long streamAdvance(long connection, long stream);

    /**
     * Direct views onto the current batch's Arrow buffers, one entry per column.
     *
     * <p>Each entry is {@code {long[] meta, ByteBuffer validity, ByteBuffer b1,
     * ByteBuffer b2}} where {@code meta} is {@code {length, offset, nullCount}}. Buffers chDB did not
     * supply are null. One call per batch, not per cell.
     *
     * <p>The buffers are owned by the batch. {@link #streamAdvance(long, long)} and {@link
     * #streamClose(long)} free them, so the Java side must drop its references first --
     * reading a buffer whose batch has been released is a use-after-free the JVM cannot
     * catch. {@code ArrowBatch} is what enforces that; nothing else should call this.
     */
    public static native Object[] streamBatchColumns(long stream);

    /** {@code chdb_stream_cancel_query}. Safe to call on a stream that already ended. */
    public static native void streamCancel(long connection, long stream);

    /**
     * Releases the current batch, the Arrow schema and the engine's stream handle.
     * Idempotent.
     */
    public static native void streamClose(long stream);

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
     * v26.7.2-rc.2, which is the pinned baseline, so it is resolved by {@code dlsym} rather
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
