package org.chdb.internal;

import java.util.List;

/**
 * A row-at-a-time view over a {@code RowBinaryWithNamesAndTypes} stream.
 *
 * <h2>How the engine frames a stream</h2>
 * Each chunk {@code chdb_stream_fetch_result} returns is a <em>complete</em>
 * {@code RowBinaryWithNamesAndTypes} document: its own header, then a whole number of rows.
 * Measured rather than assumed — for {@code SELECT number FROM numbers(200000)} every chunk
 * begins with the same 15-byte {@code 01 06 "number" 06 "UInt64"} header and every chunk's
 * length is that header plus an exact multiple of the row size.
 *
 * <p>So chunks are read as documents and not concatenated. Concatenating them is the obvious
 * first design and it is wrong in a way that does not announce itself: the second chunk's header
 * gets decoded as row data, and a stream of 200,000 rows reads back as 200,005 of which five are
 * the header bytes of later chunks reinterpreted as numbers. Plausible values, quietly wrong.
 *
 * <p>Because that framing is an observed behaviour rather than a documented contract, a chunk
 * that ends part-way through a row is reported as an error naming the assumption instead of
 * being stitched to the next one. If a future engine frames differently, this stops rather than
 * mis-reads.
 *
 * <p>The same shape covers the materialised route: one buffer with one header is a stream of
 * exactly one chunk.
 */
public final class RowBinaryCursor implements AutoCloseable {

    /** Where chunks come from, so the framing can be tested without an engine. */
    public interface ChunkSource extends AutoCloseable {
        /** The next chunk, or null at end of stream. */
        byte[] next();

        /**
         * Asks the producer to stop, if it has one to ask.
         *
         * <p>Default no-op: a source over an already-materialised buffer has nothing to
         * cancel, which is also true of the engine's non-streamable statements.
         */
        default void cancel() {
        }

        @Override
        void close();
    }

    private final ChunkSource chunks;
    private final RowBinaryDecoder.Options options;
    private final RowBinaryHeader header;
    private final Object[] row;

    private RowBinaryInput current;
    private boolean exhausted;
    private boolean closed;
    private long rowNumber;

    public RowBinaryCursor(ChunkSource chunks, RowBinaryDecoder.Options options) {
        this.chunks = chunks;
        this.options = options;
        // Eagerly, because JDBC requires getMetaData() to answer before the first next() and
        // the header is where the names and types are. An empty result still has a header.
        byte[] first = chunks.next();
        if (first == null || first.length == 0) {
            throw new IllegalStateException(
                    "the RowBinary stream produced no header; every chunk should begin with one");
        }
        this.current = new RowBinaryInput(first);
        this.header = RowBinaryHeader.read(current);
        this.row = new Object[header.columnCount()];
    }

    public RowBinaryHeader header() {
        return header;
    }

    /** What the engine was asked for, which the accessor layer needs for the session zone. */
    public RowBinaryDecoder.Options options() {
        return options;
    }

    /** Asks the engine to stop producing, if the source can. */
    public void cancel() {
        chunks.cancel();
    }

    /** One-based, and zero before the first row, as {@code ResultSet.getRow()} wants it. */
    public long rowNumber() {
        return rowNumber;
    }

    /**
     * Decodes the next row.
     *
     * @return false once the stream has ended
     */
    public boolean next() {
        if (closed) {
            throw new IllegalStateException("the cursor is closed");
        }
        while (true) {
            if (current != null && current.hasRemaining()) {
                decodeRow();
                rowNumber++;
                return true;
            }
            if (!nextChunk()) {
                return false;
            }
        }
    }

    private void decodeRow() {
        List<ClickHouseType> types = header.types();
        for (int i = 0; i < row.length; i++) {
            try {
                row[i] = RowBinaryDecoder.decode(types.get(i), current, options);
            } catch (IllegalStateException truncated) {
                throw new IllegalStateException(
                        "a chunk ended part-way through row " + (rowNumber + 1) + ", at column "
                                + (i + 1) + " (" + header.names().get(i) + "). Each chunk is"
                                + " expected to be a complete RowBinaryWithNamesAndTypes document"
                                + " containing whole rows.",
                        truncated);
            }
        }
    }

    /**
     * Moves to the next chunk and reads its header.
     *
     * @return false if the stream had no more chunks
     */
    private boolean nextChunk() {
        if (exhausted) {
            current = null;
            return false;
        }
        byte[] chunk = chunks.next();
        if (chunk == null || chunk.length == 0) {
            exhausted = true;
            current = null;
            return false;
        }
        RowBinaryInput in = new RowBinaryInput(chunk);
        RowBinaryHeader next = RowBinaryHeader.read(in);
        // A schema that changes mid-stream would mean decoding later rows against the wrong
        // types. It should not happen; noticing beats trusting.
        if (!sameShape(next)) {
            throw new IllegalStateException(
                    "the stream's columns changed mid-stream: was " + describe(header)
                            + ", now " + describe(next));
        }
        current = in;
        return true;
    }

    private boolean sameShape(RowBinaryHeader other) {
        if (other.columnCount() != header.columnCount()) {
            return false;
        }
        for (int i = 0; i < header.columnCount(); i++) {
            if (!header.names().get(i).equals(other.names().get(i))
                    || !header.types().get(i).name().equals(other.types().get(i).name())) {
                return false;
            }
        }
        return true;
    }

    private static String describe(RowBinaryHeader h) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < h.columnCount(); i++) {
            sb.append(i > 0 ? ", " : "").append(h.names().get(i)).append(' ')
                    .append(h.types().get(i).name());
        }
        return sb.append(')').toString();
    }

    /** The decoded value of one column of the current row, zero-based. */
    public Object value(int index) {
        return row[index];
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        current = null;
        chunks.close();
    }
}
