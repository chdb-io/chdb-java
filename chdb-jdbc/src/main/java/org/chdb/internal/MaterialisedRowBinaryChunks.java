package org.chdb.internal;

/**
 * A one-chunk source over a statement the engine will not stream.
 *
 * <p>{@code chdb_stream_query} admits only a SELECT pipeline; {@code SHOW}, {@code DESCRIBE},
 * {@code EXPLAIN}, {@code EXISTS} and {@code CHECK} are refused with "Streaming query is not
 * supported". They run through {@code chdb_query_with_params_n} instead, which returns the whole
 * result in one buffer — and a buffer with one header and its rows is a stream of exactly one
 * chunk, so the cursor above does not need to know the difference.
 *
 * <p>Two things a caller has to know. Memory is not bounded by a chunk: the result is
 * materialised in the engine before this returns, which is safe only because these statements'
 * results are bounded by a schema rather than by data. And there is nothing to cancel — the
 * statement has finished by the time there is anything to cancel it with.
 *
 * <p>Unlike the Arrow route this replaces, parameters work here: {@code chdb_query_arrow_n} had
 * no {@code _with_params_n} variant, so a parameterised {@code SHOW} or {@code DESCRIBE} had no
 * path at all and the driver had to refuse it.
 */
public final class MaterialisedRowBinaryChunks implements RowBinaryCursor.ChunkSource {

    private byte[] pending;

    private MaterialisedRowBinaryChunks(byte[] pending) {
        this.pending = pending;
    }

    /**
     * Runs the statement and takes its whole result.
     *
     * @throws ChdbNativeException carrying the engine's error text if the statement failed
     */
    public static MaterialisedRowBinaryChunks run(
            long connection, byte[] sql, byte[][] paramNames, byte[][] paramValues) {
        long result =
                ChdbNative.query(
                        connection,
                        sql,
                        Utf8Bytes.of(NativeRowBinaryChunks.FORMAT),
                        paramNames,
                        paramValues);
        try {
            byte[] bytes = ChdbNative.resultBytes(result);
            return new MaterialisedRowBinaryChunks(bytes);
        } finally {
            // Released here rather than by the cursor: the bytes are already a Java array, so
            // there is nothing left for the engine's result to own.
            ChdbNative.destroyResult(result);
        }
    }

    @Override
    public byte[] next() {
        byte[] out = pending;
        pending = null;
        return out;
    }

    @Override
    public void close() {
        pending = null;
    }
}
