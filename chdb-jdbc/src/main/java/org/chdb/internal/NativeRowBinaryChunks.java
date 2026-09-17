package org.chdb.internal;

/**
 * Chunks straight from the engine's stream.
 *
 * <p>Thin on purpose: the shim owns the engine handle and copies each chunk out before
 * releasing it, so there is no native memory to keep alive here and nothing to get wrong about
 * lifetimes. The interesting part — that a chunk can end part-way through a row — belongs to
 * {@link RowBinaryCursor}, which is why this is separable and why that part is testable without
 * an engine at all.
 */
public final class NativeRowBinaryChunks implements RowBinaryCursor.ChunkSource {

    /** {@code RowBinaryWithNamesAndTypes}: the format whose header carries the real types. */
    public static final String FORMAT = "RowBinaryWithNamesAndTypes";

    private final long connection;
    private final long stream;
    private boolean closed;

    private NativeRowBinaryChunks(long connection, long stream) {
        this.connection = connection;
        this.stream = stream;
    }

    /**
     * Starts a stream.
     *
     * @throws ChdbNativeException carrying the engine's error text if the statement failed
     */
    public static NativeRowBinaryChunks open(
            long connection, byte[] sql, byte[][] paramNames, byte[][] paramValues) {
        long stream =
                ChdbNative.rowBinaryOpen(
                        connection, sql, Utf8Bytes.of(FORMAT), paramNames, paramValues);
        return new NativeRowBinaryChunks(connection, stream);
    }

    /** The engine's stream handle, for the cancel path. */
    public long streamHandle() {
        return stream;
    }

    @Override
    public byte[] next() {
        if (closed) {
            return null;
        }
        return ChdbNative.rowBinaryFetch(connection, stream);
    }

    /**
     * Asks the engine to stop producing.
     *
     * <p>Separate from {@link #close()} because cancelling is what a {@code Statement.cancel}
     * from another thread does, and it has to be safe while this thread is inside
     * {@link #next()}.
     */
    public void cancel() {
        if (!closed) {
            ChdbNative.rowBinaryCancel(connection, stream);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ChdbNative.rowBinaryClose(stream);
    }
}
