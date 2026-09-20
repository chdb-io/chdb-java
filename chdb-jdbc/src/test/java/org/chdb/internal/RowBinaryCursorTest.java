package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chunk framing.
 *
 * <p>The engine frames each chunk as a complete {@code RowBinaryWithNamesAndTypes} document —
 * its own header, then whole rows — which the cursor relies on. The stream below is a real
 * five-row response; the tests re-frame it into several chunks the way the engine would and
 * insist the same rows come out, then check that the two ways the assumption could be violated
 * are reported rather than mis-read.
 *
 * <p>Worth recording why this is not a "split the bytes anywhere" test. That was the first
 * design and it was wrong: concatenating chunks makes the second chunk's header decode as row
 * data, so 200,000 rows read back as 200,005 with header fragments reinterpreted as numbers.
 * Plausible values, quietly wrong — which is the class of bug this whole change exists to
 * remove.
 */
class RowBinaryCursorTest {

    /**
     * Five rows of {@code number}, {@code toString(number)}, a {@code Date}, a
     * {@code Nullable(Int32)} that is null on the even rows, and {@code [number, number + 1]}.
     *
     * <p>Chosen for what it puts on the wire: a fixed-width column, a length-prefixed one, a
     * date, a Nullable that is null on some rows and not others, and an array whose length is a
     * varint.
     */
    private static final String STREAM = "05016e01730164056d61796265036172720655496e74363406537472696e6704446174650f4e756c6c61626c6528496e743332290d41727261792855496e7436342900000000000000000130e64f01020000000000000000010000000000000001000000000000000131e74f0001000000020100000000000000020000000000000002000000000000000132e84f01020200000000000000030000000000000003000000000000000133e94f0003000000020300000000000000040000000000000004000000000000000134ea4f010204000000000000000500000000000000";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final RowBinaryDecoder.Options OPTIONS =
            new RowBinaryDecoder.Options(true, ZoneOffset.UTC);

    private static final class Chunks implements RowBinaryCursor.ChunkSource {
        private final Deque<byte[]> queue;
        boolean closed;

        Chunks(List<byte[]> chunks) {
            this.queue = new ArrayDeque<>(chunks);
        }

        @Override
        public byte[] next() {
            return queue.isEmpty() ? null : queue.poll();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Where the header ends, and where each row starts, in the captured document. */
    private static int[] rowOffsets(byte[] all) {
        RowBinaryInput in = new RowBinaryInput(all);
        RowBinaryHeader h = RowBinaryHeader.read(in);
        List<Integer> offsets = new ArrayList<>();
        offsets.add(in.position());
        while (in.hasRemaining()) {
            for (ClickHouseType t : h.types()) {
                RowBinaryDecoder.decode(t, in, OPTIONS);
            }
            offsets.add(in.position());
        }
        int[] out = new int[offsets.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = offsets.get(i);
        }
        return out;
    }

    /** Re-frames the document into chunks of {@code rowsPerChunk} rows, each with its header. */
    private static List<byte[]> reframe(byte[] all, int rowsPerChunk) {
        int[] offsets = rowOffsets(all);
        byte[] header = Arrays.copyOfRange(all, 0, offsets[0]);
        int rows = offsets.length - 1;
        List<byte[]> chunks = new ArrayList<>();
        for (int first = 0; first < rows; first += rowsPerChunk) {
            int last = Math.min(first + rowsPerChunk, rows);
            byte[] body = Arrays.copyOfRange(all, offsets[first], offsets[last]);
            byte[] chunk = new byte[header.length + body.length];
            System.arraycopy(header, 0, chunk, 0, header.length);
            System.arraycopy(body, 0, chunk, header.length, body.length);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static List<String> readAll(List<byte[]> chunks) {
        List<String> rows = new ArrayList<>();
        try (RowBinaryCursor cursor = new RowBinaryCursor(new Chunks(chunks), OPTIONS)) {
            assertEquals(5, cursor.header().columnCount());
            while (cursor.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cursor.header().columnCount(); i++) {
                    sb.append(i > 0 ? " | " : "").append(show(cursor.value(i)));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    private static String show(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        if (v.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? ", " : "").append(show(java.lang.reflect.Array.get(v, i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    @Test
    @DisplayName("one chunk holding the whole result reads the rows it should")
    void singleChunk() {
        List<String> rows = readAll(Arrays.asList(hex(STREAM)));
        assertEquals(5, rows.size());
        assertEquals("0 | 0 | 2026-01-01 | null | [0, 1]", rows.get(0));
        assertEquals("1 | 1 | 2026-01-02 | 1 | [1, 2]", rows.get(1));
        assertEquals("4 | 4 | 2026-01-05 | null | [4, 5]", rows.get(4));
    }

    @Test
    @DisplayName("every chunking of the rows gives the same rows back")
    void everyChunking() {
        byte[] all = hex(STREAM);
        List<String> expected = readAll(Arrays.asList(all));
        for (int perChunk = 1; perChunk <= 6; perChunk++) {
            assertEquals(expected, readAll(reframe(all, perChunk)), perChunk + " row(s) per chunk");
        }
    }

    @Test
    @DisplayName("a header-only stream is an empty result, not an error")
    void emptyResult() {
        byte[] all = hex(STREAM);
        byte[] headerOnly = Arrays.copyOfRange(all, 0, rowOffsets(all)[0]);
        try (RowBinaryCursor cursor =
                new RowBinaryCursor(new Chunks(Arrays.asList(headerOnly)), OPTIONS)) {
            assertEquals(5, cursor.header().columnCount());
            assertFalse(cursor.next());
            assertEquals(0, cursor.rowNumber());
        }
    }

    @Test
    @DisplayName("a chunk that ends mid-row is reported, not stitched to the next")
    void chunkEndingMidRow() {
        byte[] all = hex(STREAM);
        int[] offsets = rowOffsets(all);
        byte[] cut = Arrays.copyOfRange(all, 0, offsets[2] - 3);
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> readAll(Arrays.asList(cut)));
        assertTrue(e.getMessage().contains("part-way through row"), e.getMessage());
        assertTrue(e.getMessage().contains("complete RowBinaryWithNamesAndTypes"), e.getMessage());
    }

    @Test
    @DisplayName("a schema that changes between chunks is refused")
    void schemaChangeRefused() {
        byte[] all = hex(STREAM);
        List<byte[]> chunks = new ArrayList<>(reframe(all, 2));
        // A one-column document appended to a five-column stream.
        chunks.add(hex("0101780655496e7436340100000000000000"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> readAll(chunks));
        assertTrue(e.getMessage().contains("columns changed mid-stream"), e.getMessage());
    }

    @Test
    @DisplayName("closing the cursor closes the chunk source")
    void closePropagates() {
        Chunks chunks = new Chunks(Arrays.asList(hex(STREAM)));
        RowBinaryCursor cursor = new RowBinaryCursor(chunks, OPTIONS);
        assertTrue(cursor.next());
        cursor.close();
        assertTrue(chunks.closed);
        cursor.close();
        assertThrows(IllegalStateException.class, cursor::next);
    }

    @Test
    @DisplayName("the row number counts from one and stops at the end")
    void rowNumbers() {
        try (RowBinaryCursor cursor =
                new RowBinaryCursor(new Chunks(reframe(hex(STREAM), 2)), OPTIONS)) {
            assertEquals(0, cursor.rowNumber());
            for (long expected = 1; expected <= 5; expected++) {
                assertTrue(cursor.next());
                assertEquals(expected, cursor.rowNumber());
            }
            assertFalse(cursor.next());
            assertEquals(5, cursor.rowNumber());
        }
    }
}
