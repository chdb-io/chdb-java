package org.chdb.internal;

/**
 * ClickHouse's binary encoding of a data type.
 *
 * <p>A {@code Dynamic} column prefixes every value with the encoded type of that value, since
 * each row may hold a different one. Reading it is therefore a prerequisite for reading
 * {@code Dynamic} at all.
 *
 * <p>The encoding is specified in the engine's own
 * {@code src/DataTypes/DataTypesBinaryEncoding.h}, whose header comment is the table this
 * follows. Rather than build a second type model, each tag is turned back into the type
 * <em>name</em> the engine would print and handed to {@link ClickHouseType#parse} — the same
 * approach clickhouse-jdbc takes, and it means a type reached through {@code Dynamic} and the
 * same type named in a header are the same object afterwards.
 *
 * <p>Tags this does not implement — {@code Set}, {@code Function}, {@code AggregateFunction},
 * {@code JSON}, {@code QBit} — throw rather than return something approximate. A wrong type
 * here would decode the following bytes as the wrong thing, which corrupts the rest of the row.
 */
final class BinaryTypeEncoding {

    private BinaryTypeEncoding() {
    }

    static final class UnsupportedEncodingException extends RuntimeException {
        UnsupportedEncodingException(String message) {
            super(message);
        }
    }

    /** Reads one encoded type, advancing the cursor past it. */
    static ClickHouseType read(RowBinaryInput in) {
        return ClickHouseType.parse(readName(in));
    }

    private static String readName(RowBinaryInput in) {
        int tag = in.readUInt8();
        switch (tag) {
            case 0x00: return "Nothing";
            case 0x01: return "UInt8";
            case 0x02: return "UInt16";
            case 0x03: return "UInt32";
            case 0x04: return "UInt64";
            case 0x05: return "UInt128";
            case 0x06: return "UInt256";
            case 0x07: return "Int8";
            case 0x08: return "Int16";
            case 0x09: return "Int32";
            case 0x0A: return "Int64";
            case 0x0B: return "Int128";
            case 0x0C: return "Int256";
            case 0x0D: return "Float32";
            case 0x0E: return "Float64";
            case 0x0F: return "Date";
            case 0x10: return "Date32";
            case 0x11: return "DateTime";
            case 0x12: return "DateTime(" + quote(in.readString()) + ")";
            case 0x13: return "DateTime64(" + in.readUInt8() + ")";
            case 0x14: {
                int precision = in.readUInt8();
                return "DateTime64(" + precision + ", " + quote(in.readString()) + ")";
            }
            case 0x15: return "String";
            case 0x16: return "FixedString(" + in.readVarUInt() + ")";
            case 0x17: return readEnum(in, 8);
            case 0x18: return readEnum(in, 16);
            case 0x19: return decimal(in, 32);
            case 0x1A: return decimal(in, 64);
            case 0x1B: return decimal(in, 128);
            case 0x1C: return decimal(in, 256);
            case 0x1D: return "UUID";
            case 0x1E: return "Array(" + readName(in) + ")";
            case 0x1F: return readTuple(in, false);
            case 0x20: return readTuple(in, true);
            case 0x22: return intervalKind(in.readUInt8());
            case 0x23: return "Nullable(" + readName(in) + ")";
            case 0x26: return "LowCardinality(" + readName(in) + ")";
            case 0x27: {
                String key = readName(in);
                return "Map(" + key + ", " + readName(in) + ")";
            }
            case 0x28: return "IPv4";
            case 0x29: return "IPv6";
            case 0x2A: return readVariant(in);
            case 0x2B:
                // max_types is a property of the column, not of the value, and nothing in
                // decoding depends on it.
                in.readUInt8();
                return "Dynamic";
            case 0x2C:
                // The named custom types: Point, Ring, Polygon, and anything added later.
                return in.readString();
            case 0x2D: return "Bool";
            case 0x2F: return readNested(in);
            case 0x31: return "BFloat16";
            case 0x32: return "Time";
            case 0x34: return "Time64(" + in.readUInt8() + ")";
            default:
                throw new UnsupportedEncodingException(
                        String.format("no reader for binary type tag 0x%02X", tag));
        }
    }

    private static String decimal(RowBinaryInput in, int width) {
        int precision = in.readUInt8();
        int scale = in.readUInt8();
        // Named Decimal(P, S) rather than Decimal32(S): the width is implied by the precision,
        // and this is the spelling the engine itself prints in a header, so both routes to the
        // same type produce the same name.
        return "Decimal(" + precision + ", " + scale + ")";
    }

    private static String readEnum(RowBinaryInput in, int bits) {
        long count = in.readVarUInt();
        StringBuilder sb = new StringBuilder(bits == 8 ? "Enum8(" : "Enum16(");
        for (long i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String label = in.readString();
            long value = bits == 8 ? in.readInt8() : in.readInt16();
            sb.append(quote(label)).append(" = ").append(value);
        }
        return sb.append(')').toString();
    }

    private static String readTuple(RowBinaryInput in, boolean named) {
        long count = in.readVarUInt();
        StringBuilder sb = new StringBuilder("Tuple(");
        for (long i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (named) {
                sb.append(quote(in.readString())).append(' ');
            }
            sb.append(readName(in));
        }
        return sb.append(')').toString();
    }

    private static String readNested(RowBinaryInput in) {
        long count = in.readVarUInt();
        StringBuilder sb = new StringBuilder("Nested(");
        for (long i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(in.readString())).append(' ').append(readName(in));
        }
        return sb.append(')').toString();
    }

    private static String readVariant(RowBinaryInput in) {
        long count = in.readVarUInt();
        StringBuilder sb = new StringBuilder("Variant(");
        for (long i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(readName(in));
        }
        return sb.append(')').toString();
    }

    private static String intervalKind(int kind) {
        switch (kind) {
            case 0x00: return "IntervalNanosecond";
            case 0x01: return "IntervalMicrosecond";
            case 0x02: return "IntervalMillisecond";
            case 0x03: return "IntervalSecond";
            case 0x04: return "IntervalMinute";
            case 0x05: return "IntervalHour";
            case 0x06: return "IntervalDay";
            case 0x07: return "IntervalWeek";
            case 0x08: return "IntervalMonth";
            case 0x09: return "IntervalQuarter";
            // Not 0x0A. The engine's own table says 0x1A for Year, and following the table is
            // the only way to be right about it.
            case 0x1A: return "IntervalYear";
            default:
                throw new UnsupportedEncodingException(
                        String.format("unknown interval kind 0x%02X", kind));
        }
    }

    /** Single-quoted the way ClickHouse prints a label, so the parser reads it back unchanged. */
    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('\'');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
