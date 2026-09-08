package org.chdb.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Typed, bounds-checked reads over one Arrow record batch owned by the JNI shim.
 *
 * <h2>Lifetime</h2>
 * The {@link ByteBuffer}s are direct views onto native memory the shim frees when the stream
 * advances or closes. A {@code DirectByteBuffer} stays a valid Java object after its memory
 * is gone, so reading one then is a use-after-free the JVM will not catch -- it is a segfault
 * or silent garbage, not an exception.
 *
 * <p>{@link #invalidate()} is what prevents that: it drops every buffer reference and flips a
 * flag, and it must be called <em>before</em> the shim releases the batch. Every accessor
 * checks the flag first, so a read that arrives late gets an {@link IllegalStateException}.
 * {@code ChdbResultSet} is the only caller, and it invalidates before every advance and close.
 *
 * <h2>Indexing</h2>
 * Callers pass logical row numbers, 0-based within the batch. The Arrow array offset (already
 * folded together with the parent struct's offset by the shim) is added here, so nothing
 * above this class has to know about it.
 */
public final class ArrowBatch {

    private final ArrowFieldType[] types;
    private final long rowCount;

    private ByteBuffer[] validity;
    private ByteBuffer[] primary;
    private ByteBuffer[] data;
    private long[] offsets;
    private long[] nullCounts;
    private boolean valid = true;

    /**
     * @param columns the shim's {@code streamBatchColumns} return value
     * @param types parsed column types, in ordinal order
     * @param rowCount rows in this batch, as reported by {@code streamAdvance}
     */
    public ArrowBatch(Object[] columns, ArrowFieldType[] types, long rowCount) {
        this.types = types;
        this.rowCount = rowCount;

        int count = types.length;
        this.validity = new ByteBuffer[count];
        this.primary = new ByteBuffer[count];
        this.data = new ByteBuffer[count];
        this.offsets = new long[count];
        this.nullCounts = new long[count];

        for (int i = 0; i < count; i++) {
            Object[] entry = (Object[]) columns[i];
            long[] meta = (long[]) entry[0];
            offsets[i] = meta[1];
            nullCounts[i] = meta[2];
            validity[i] = order(entry[1]);
            primary[i] = order(entry[2]);
            data[i] = order(entry[3]);
        }
    }

    /**
     * A direct buffer from JNI defaults to big-endian regardless of the machine, while Arrow
     * buffers are in native byte order. Without this, every multi-byte read on a
     * little-endian machine -- which both supported architectures are -- comes back
     * byte-swapped.
     */
    private static ByteBuffer order(Object buffer) {
        if (buffer == null) {
            return null;
        }
        return ((ByteBuffer) buffer).order(ByteOrder.nativeOrder());
    }

    public long rowCount() {
        return rowCount;
    }

    /**
     * Drops every reference to native memory. Call before the shim releases the batch; after
     * this, every accessor throws rather than reading freed memory.
     */
    public void invalidate() {
        valid = false;
        validity = null;
        primary = null;
        data = null;
        offsets = null;
        nullCounts = null;
    }

    private void checkValid() {
        if (!valid) {
            throw new IllegalStateException(
                    "this Arrow batch has been released; its buffers are no longer readable");
        }
    }

    private int physical(int column, int row) {
        checkValid();
        if (row < 0 || row >= rowCount) {
            throw new IllegalStateException(
                    "row " + row + " is outside this batch of " + rowCount + " rows");
        }
        long index = offsets[column] + row;
        if (index > Integer.MAX_VALUE) {
            // Arrow indices are int64, but a ByteBuffer is addressed with an int. A batch
            // that large cannot be read through this path; chDB's batches are far smaller.
            throw new IllegalStateException(
                    "Arrow index " + index + " exceeds the addressable range of a ByteBuffer");
        }
        return (int) index;
    }

    public boolean isNull(int column, int row) {
        int index = physical(column, row);
        if (nullCounts[column] == 0) {
            return false;
        }
        ByteBuffer bitmap = validity[column];
        if (bitmap == null) {
            // No validity buffer means every slot is valid, whatever null_count claims.
            return false;
        }
        int byteIndex = index >>> 3;
        if (byteIndex >= bitmap.limit()) {
            return true;
        }
        return (bitmap.get(byteIndex) & (1 << (index & 7))) == 0;
    }

    public boolean getBoolean(int column, int row) {
        int index = physical(column, row);
        ByteBuffer bits = require(primary[column], column, "boolean data");
        int byteIndex = index >>> 3;
        if (byteIndex >= bits.limit()) {
            return false;
        }
        return (bits.get(byteIndex) & (1 << (index & 7))) != 0;
    }

    /**
     * Any integral column widened to {@code long}.
     *
     * <p>Unsigned kinds are zero-extended, so a UInt32 of 4e9 reads as 4e9 rather than as a
     * negative int. UInt64 is the one that does not fit and is rejected here; {@link
     * #getBigInteger(int, int)} is its accessor.
     */
    public long getLong(int column, int row) {
        int index = physical(column, row);
        ArrowFieldType type = types[column];
        ByteBuffer buffer = require(primary[column], column, "integer data");
        switch (type.kind()) {
            case INT8:
                return buffer.get(index);
            case UINT8:
                return buffer.get(index) & 0xFFL;
            case INT16:
                return buffer.getShort(index * 2);
            case UINT16:
                return buffer.getShort(index * 2) & 0xFFFFL;
            case INT32:
            case DATE32:
                return buffer.getInt(index * 4);
            case UINT32:
                return buffer.getInt(index * 4) & 0xFFFFFFFFL;
            case INT64:
            case DATE64:
            case TIMESTAMP:
            case DURATION:
                return buffer.getLong(index * 8);
            case TIME32:
                return buffer.getInt(index * 4);
            case TIME64:
                return buffer.getLong(index * 8);
            case UINT64:
            {
                long raw = buffer.getLong(index * 8);
                if (raw < 0) {
                    throw new ArithmeticException(
                            "UInt64 value " + Long.toUnsignedString(raw)
                                    + " is larger than Long.MAX_VALUE. Read it with getObject() or"
                                    + " getBigDecimal(), which return the exact value.");
                }
                return raw;
            }
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not an integer");
        }
    }

    public double getDouble(int column, int row) {
        int index = physical(column, row);
        ArrowFieldType type = types[column];
        ByteBuffer buffer = require(primary[column], column, "floating-point data");
        switch (type.kind()) {
            case FLOAT16:
                return decodeFloat16(buffer.getShort(index * 2));
            case FLOAT32:
                return buffer.getFloat(index * 4);
            case FLOAT64:
                return buffer.getDouble(index * 8);
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not floating-point");
        }
    }

    /**
     * IEEE-754 binary16 to double. The JDK has no float16 primitive before Java 20, and V1
     * targets Java 11, so the conversion is done by hand.
     */
    private static double decodeFloat16(short bits) {
        int sign = (bits >>> 15) & 0x1;
        int exponent = (bits >>> 10) & 0x1F;
        int mantissa = bits & 0x3FF;
        double magnitude;
        if (exponent == 0) {
            // Subnormal: no implicit leading one.
            magnitude = mantissa * Math.pow(2, -24);
        } else if (exponent == 0x1F) {
            magnitude = mantissa == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        } else {
            magnitude = (1 + mantissa / 1024.0) * Math.pow(2, exponent - 15);
        }
        return sign == 1 ? -magnitude : magnitude;
    }

    public BigInteger getBigInteger(int column, int row) {
        int index = physical(column, row);
        ArrowFieldType type = types[column];
        if (type.kind() == ArrowFieldType.Kind.UINT64) {
            ByteBuffer buffer = require(primary[column], column, "UInt64 data");
            long raw = buffer.getLong(index * 8);
            // Long.toUnsignedString round-trips exactly and avoids a two's-complement
            // correction that is easy to get subtly wrong.
            return new BigInteger(Long.toUnsignedString(raw));
        }
        if (type.kind() == ArrowFieldType.Kind.DECIMAL) {
            return decimalUnscaled(column, index, type);
        }
        return BigInteger.valueOf(getLong(column, row));
    }

    public BigDecimal getBigDecimal(int column, int row) {
        int index = physical(column, row);
        ArrowFieldType type = types[column];
        switch (type.kind()) {
            case DECIMAL:
                return new BigDecimal(decimalUnscaled(column, index, type), type.scale());
            case UINT64:
                return new BigDecimal(getBigInteger(column, row));
            case FLOAT16:
            case FLOAT32:
            case FLOAT64:
                // valueOf goes through Double.toString, which yields the shortest decimal
                // that round-trips, rather than the exact binary expansion nobody wants.
                return BigDecimal.valueOf(getDouble(column, row));
            case BOOL:
                return getBoolean(column, row) ? BigDecimal.ONE : BigDecimal.ZERO;
            default:
                return BigDecimal.valueOf(getLong(column, row));
        }
    }

    /** Reads a decimal's little-endian two's-complement value as a {@link BigInteger}. */
    private BigInteger decimalUnscaled(int column, int index, ArrowFieldType type) {
        ByteBuffer buffer = require(primary[column], column, "decimal data");
        int width = type.byteWidth();
        byte[] littleEndian = new byte[width];
        int start = index * width;
        for (int i = 0; i < width; i++) {
            littleEndian[i] = buffer.get(start + i);
        }
        // BigInteger's byte[] constructor reads big-endian two's complement, so reverse.
        byte[] bigEndian = new byte[width];
        for (int i = 0; i < width; i++) {
            bigEndian[i] = littleEndian[width - 1 - i];
        }
        return new BigInteger(bigEndian);
    }

    public String getString(int column, int row) {
        byte[] bytes = getBytes(column, row);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public byte[] getBytes(int column, int row) {
        int index = physical(column, row);
        ArrowFieldType type = types[column];
        switch (type.kind()) {
            case UTF8:
            case BINARY:
            {
                ByteBuffer offsetBuffer = require(primary[column], column, "offsets");
                int start = offsetBuffer.getInt(index * 4);
                int end = offsetBuffer.getInt((index + 1) * 4);
                return slice(column, start, end - start);
            }
            case LARGE_UTF8:
            case LARGE_BINARY:
            {
                ByteBuffer offsetBuffer = require(primary[column], column, "offsets");
                long start = offsetBuffer.getLong(index * 8);
                long end = offsetBuffer.getLong((index + 1) * 8);
                if (end - start > Integer.MAX_VALUE) {
                    throw new IllegalStateException(
                            "value in column " + (column + 1) + " is " + (end - start)
                                    + " bytes, which does not fit in a Java array");
                }
                return slice(column, (int) start, (int) (end - start));
            }
            case FIXED_SIZE_BINARY:
            {
                ByteBuffer buffer = require(primary[column], column, "fixed-size binary data");
                int width = type.byteWidth();
                byte[] out = new byte[width];
                for (int i = 0; i < width; i++) {
                    out[i] = buffer.get(index * width + i);
                }
                return out;
            }
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not a byte sequence");
        }
    }

    private byte[] slice(int column, int start, int length) {
        ByteBuffer buffer = data[column];
        if (length == 0) {
            // An empty value is legitimate and its data buffer may be absent entirely, so
            // this must not go through require().
            return new byte[0];
        }
        if (buffer == null) {
            throw new IllegalStateException(
                    "column " + (column + 1) + " has a non-empty value but chDB supplied no data buffer");
        }
        if (start < 0 || length < 0 || start + length > buffer.limit()) {
            throw new IllegalStateException(
                    "value in column " + (column + 1) + " spans bytes " + start + ".." + (start + length)
                            + " but its data buffer holds only " + buffer.limit()
                            + " bytes; the Arrow buffers are inconsistent");
        }
        byte[] out = new byte[length];
        // Absolute duplicate-then-read: the shared buffer's position must not move, because
        // another thread may be reading a different row of the same column.
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.get(out, 0, length);
        return out;
    }

    public LocalDate getLocalDate(int column, int row) {
        ArrowFieldType type = types[column];
        long raw = getLong(column, row);
        switch (type.kind()) {
            case DATE32:
                return LocalDate.ofEpochDay(raw);
            case DATE64:
                return LocalDate.ofEpochDay(Math.floorDiv(raw, 86_400_000L));
            case TIMESTAMP:
                return LocalDate.ofEpochDay(Math.floorDiv(toEpochSecond(raw, type), 86_400L));
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not a date");
        }
    }

    public Instant getInstant(int column, int row) {
        ArrowFieldType type = types[column];
        long raw = getLong(column, row);
        switch (type.kind()) {
            case TIMESTAMP:
                return Instant.ofEpochSecond(toEpochSecond(raw, type), subsecondNanos(raw, type));
            case DATE32:
                return Instant.ofEpochSecond(raw * 86_400L);
            case DATE64:
                return Instant.ofEpochMilli(raw);
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not a timestamp");
        }
    }

    public LocalTime getLocalTime(int column, int row) {
        ArrowFieldType type = types[column];
        long raw = getLong(column, row);
        switch (type.kind()) {
            case TIME32:
            case TIME64:
                return LocalTime.ofNanoOfDay(scaleToNanos(raw, type));
            case TIMESTAMP:
            {
                long seconds = Math.floorMod(toEpochSecond(raw, type), 86_400L);
                return LocalTime.ofNanoOfDay(seconds * 1_000_000_000L + subsecondNanos(raw, type));
            }
            default:
                throw new IllegalStateException(
                        "column " + (column + 1) + " is " + type.typeName() + ", not a time");
        }
    }

    /**
     * Whole seconds of a temporal value, rounding toward negative infinity.
     *
     * <p>{@code floorDiv}, not {@code /}: a pre-epoch microsecond timestamp of -1 is 1 µs
     * before the epoch, which is second -1 with 999999 µs, and truncating division would put
     * it in second 0.
     */
    private static long toEpochSecond(long raw, ArrowFieldType type) {
        switch (type.unit()) {
            case SECOND:
                return raw;
            case MILLI:
                return Math.floorDiv(raw, 1_000L);
            case MICRO:
                return Math.floorDiv(raw, 1_000_000L);
            case NANO:
            default:
                return Math.floorDiv(raw, 1_000_000_000L);
        }
    }

    private static int subsecondNanos(long raw, ArrowFieldType type) {
        switch (type.unit()) {
            case SECOND:
                return 0;
            case MILLI:
                return (int) Math.floorMod(raw, 1_000L) * 1_000_000;
            case MICRO:
                return (int) Math.floorMod(raw, 1_000_000L) * 1_000;
            case NANO:
            default:
                return (int) Math.floorMod(raw, 1_000_000_000L);
        }
    }

    private static long scaleToNanos(long raw, ArrowFieldType type) {
        switch (type.unit()) {
            case SECOND:
                return raw * 1_000_000_000L;
            case MILLI:
                return raw * 1_000_000L;
            case MICRO:
                return raw * 1_000L;
            case NANO:
            default:
                return raw;
        }
    }

    public UUID getUuid(int column, int row) {
        ArrowFieldType type = types[column];
        if (type.kind() == ArrowFieldType.Kind.FIXED_SIZE_BINARY && type.byteWidth() == 16) {
            byte[] bytes = getBytes(column, row);
            long high = 0;
            long low = 0;
            for (int i = 0; i < 8; i++) {
                high = (high << 8) | (bytes[i] & 0xFFL);
            }
            for (int i = 8; i < 16; i++) {
                low = (low << 8) | (bytes[i] & 0xFFL);
            }
            return new UUID(high, low);
        }
        if (type.kind() == ArrowFieldType.Kind.UTF8 || type.kind() == ArrowFieldType.Kind.LARGE_UTF8) {
            // The engine exports UUID as text unless asked for a fixed byte array, so the
            // textual form is the common case rather than a fallback.
            return UUID.fromString(getString(column, row));
        }
        throw new IllegalStateException(
                "column " + (column + 1) + " is " + type.typeName() + ", not a UUID");
    }

    private ByteBuffer require(ByteBuffer buffer, int column, String what) {
        checkValid();
        if (buffer == null) {
            ArrowFieldType type = types[column];
            if (!type.supported()) {
                throw new IllegalStateException(
                        "column " + (column + 1) + " has type " + type.typeName()
                                + ", which this driver cannot read. V1 supports the scalar type"
                                + " matrix documented in docs/type-mapping.md; cast the column in SQL"
                                + " (for example toString(col)) to read it.");
            }
            throw new IllegalStateException(
                    "chDB supplied no " + what + " buffer for column " + (column + 1)
                            + " of type " + type.typeName());
        }
        return buffer;
    }
}
