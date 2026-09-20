package org.chdb.internal;

import java.nio.charset.StandardCharsets;

/**
 * A cursor over a {@code RowBinary} buffer.
 *
 * <p>Only the primitives the format is built from: unsigned LEB128 lengths, little-endian
 * integers, and length-prefixed bytes. Everything above this reads types in terms of these.
 *
 * <p>Not thread-safe and not meant to be: one of these belongs to one result set, which the
 * statement slot already serialises.
 */
public final class RowBinaryInput {

    private final byte[] buf;
    private int pos;
    private final int limit;

    public RowBinaryInput(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public RowBinaryInput(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.pos = offset;
        this.limit = offset + length;
    }

    public int position() { return pos; }

    public boolean hasRemaining() { return pos < limit; }

    public int remaining() { return limit - pos; }

    private void need(int n) {
        if (n < 0 || pos + n > limit) {
            throw new IllegalStateException(
                    "truncated RowBinary: wanted " + n + " byte(s) at offset " + pos
                            + " but only " + (limit - pos) + " remain");
        }
    }

    public int readUInt8() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public byte readInt8() {
        need(1);
        return buf[pos++];
    }

    public int readUInt16() {
        need(2);
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public short readInt16() {
        return (short) readUInt16();
    }

    public int readInt32() {
        need(4);
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long readUInt32() {
        return readInt32() & 0xFFFFFFFFL;
    }

    public long readInt64() {
        need(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    /** {@code n} bytes as they lie, little-endian as ClickHouse wrote them. */
    public byte[] readBytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    /**
     * An unsigned LEB128 varint, which is how ClickHouse prefixes every length and count.
     *
     * <p>Returns a long because a string length is a 64-bit value in the format even though no
     * real one is; a value that cannot be a length is rejected here rather than becoming a
     * negative array size somewhere further along.
     */
    public long readVarUInt() {
        long value = 0;
        int shift = 0;
        while (true) {
            if (shift > 63) {
                throw new IllegalStateException("varint longer than 10 bytes at offset " + pos);
            }
            int b = readUInt8();
            value |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    /** A length as an int, refusing anything that could not address this buffer. */
    public int readLength() {
        long n = readVarUInt();
        if (n < 0 || n > remaining()) {
            throw new IllegalStateException(
                    "RowBinary length " + n + " at offset " + pos + " exceeds the "
                            + remaining() + " byte(s) remaining");
        }
        return (int) n;
    }

    /** A length-prefixed string. */
    public String readString() {
        int n = readLength();
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    /** A length-prefixed byte string, for a column whose bytes are not text. */
    public byte[] readByteString() {
        return readBytes(readLength());
    }
}
