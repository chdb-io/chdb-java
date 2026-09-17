package org.chdb.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decodes one value of a known ClickHouse type out of a {@code RowBinary} stream.
 *
 * <h2>What comes out</h2>
 * The nearest Java type that loses nothing, not the type JDBC hands a caller. {@code Enum8}
 * decodes to its number and not its label, because {@code getString} wants the label while
 * {@code getInt} wants the number and only the caller knows which; {@code String} and
 * {@code FixedString} decode to {@code byte[]}, because ClickHouse strings are bytes that are
 * usually but not always text and {@code getBytes} has to be able to return them intact.
 * Turning these into what an accessor returns is the layer above.
 *
 * <h2>Encodings</h2>
 * Read from ClickHouse's own serializations rather than from documentation, because two of them
 * are easy to get wrong and silent when you do:
 *
 * <ul>
 *   <li>{@code Nullable} writes a one-byte flag and then, <em>only when the value is not
 *       null</em>, the nested value. There is no placeholder to skip.
 *   <li>{@code LowCardinality} in a row-wise format writes the plain nested value:
 *       {@code SerializationLowCardinality::serializeBinary} delegates straight to the
 *       dictionary type. The dictionary framing belongs to the columnar formats.
 * </ul>
 */
public final class RowBinaryDecoder {


    private RowBinaryDecoder() {
    }

    /** Thrown for a type this decoder cannot read, naming the type rather than the offset. */
    public static final class UnsupportedTypeException extends RuntimeException {
        private final ClickHouseType type;

        UnsupportedTypeException(ClickHouseType type) {
            super("cannot decode a value of type " + type.name() + " from RowBinary");
            this.type = type;
        }

        public ClickHouseType type() {
            return type;
        }
    }

    /**
     * What the engine was asked to do, where the wire format depends on it.
     *
     * <p>{@code JSON} is the case that forces this to exist. With
     * {@code output_format_binary_write_json_as_string} the engine writes a JSON column as a
     * length-prefixed string; without it, the column's own structured binary form. **The type
     * name in the header is {@code JSON} either way**, so nothing in the stream says which one
     * arrived, and a decoder that guessed would read one as the other and hand back plausible
     * rubbish. Passing the setting in alongside the bytes makes the mismatch impossible: the
     * same code that asks the engine for the setting is the code that says so here.
     */
    public static final class Options {
        private final boolean jsonAsString;
        private final ZoneId sessionTimeZone;

        public Options(boolean jsonAsString, ZoneId sessionTimeZone) {
            this.jsonAsString = jsonAsString;
            this.sessionTimeZone = sessionTimeZone == null ? ZoneOffset.UTC : sessionTimeZone;
        }

        public boolean jsonAsString() {
            return jsonAsString;
        }

        /**
         * The engine's own timezone, from {@code SELECT timezone()}.
         *
         * <p>Needed because a {@code DateTime} column without a declared zone stores an instant
         * whose wall-clock reading is only defined relative to the session's zone, and the wall
         * clock is what a caller of {@code getTimestamp} is given.
         */
        public ZoneId sessionTimeZone() {
            return sessionTimeZone;
        }
    }

    /** Nothing assumed about the engine: JSON is refused, and UTC is the fallback zone. */
    public static final Options STRICT = new Options(false, ZoneOffset.UTC);

    /** Decodes with no assumption about engine settings. */
    public static Object decode(ClickHouseType type, RowBinaryInput in) {
        return decode(type, in, STRICT);
    }

    /**
     * Decodes one value, advancing the cursor past it.
     *
     * @return null for a SQL NULL, otherwise the value
     */
    public static Object decode(ClickHouseType type, RowBinaryInput in, Options options) {
        switch (type.kind()) {
            case NULLABLE:
                // One byte, then the value only if it is there. Reading a nested value after a
                // set flag would consume the next column's bytes and corrupt every row after.
                if (in.readUInt8() != 0) {
                    return null;
                }
                return decode(type.arguments().get(0), in, options);

            case LOW_CARDINALITY:
                return decode(type.arguments().get(0), in, options);

            case INT8: return in.readInt8();
            case INT16: return in.readInt16();
            case INT32: return in.readInt32();
            case INT64: return in.readInt64();

            // Unsigned values are widened so the top of each range is representable: UInt8 255
            // is not a Java byte and UInt64 18446744073709551615 is not a Java long.
            case UINT8: return (short) in.readUInt8();
            case UINT16: return in.readUInt16();
            case UINT32: return in.readUInt32();
            case UINT64: return unsignedBigInteger(in.readBytes(8));

            case INT128: return signedBigInteger(in.readBytes(16));
            case INT256: return signedBigInteger(in.readBytes(32));
            case UINT128: return unsignedBigInteger(in.readBytes(16));
            case UINT256: return unsignedBigInteger(in.readBytes(32));

            case FLOAT32: return Float.intBitsToFloat(in.readInt32());
            case FLOAT64: return Double.longBitsToDouble(in.readInt64());
            case BFLOAT16:
                // The top 16 bits of a float32, zero-extended.
                return Float.intBitsToFloat(in.readUInt16() << 16);

            case BOOL: return in.readUInt8() != 0;

            case STRING: return in.readByteString();
            case FIXED_STRING: return in.readBytes(type.fixedLength());

            // The number as it lies. The label is in the type, and which of the two a caller
            // wants depends on the accessor they called.
            case ENUM8: return in.readInt8();
            case ENUM16: return in.readInt16();

            case DECIMAL: return decodeDecimal(type, in);

            case DATE: return LocalDate.ofEpochDay(in.readUInt16());
            case DATE32: return LocalDate.ofEpochDay(in.readInt32());
            case DATETIME:
                return localDateTime(Instant.ofEpochSecond(in.readUInt32()), type, options);
            case DATETIME64:
                return localDateTime(decodeDateTime64Instant(type, in), type, options);
            case TIME: return LocalTime.ofSecondOfDay(Math.floorMod(in.readInt32(), 86400));
            case TIME64: return decodeTime64(type, in);

            case UUID: return decodeUuid(in);
            case IPV4: return decodeIpv4(in);
            case IPV6: return decodeIpv6(in);

            // The underlying count. Which unit it counts is in the type name.
            case INTERVAL: return in.readInt64();

            case JSON:
                // A length-prefixed string when, and only when, the engine was told to write
                // it as one. Guessing is not an option: see Options.
                if (!options.jsonAsString()) {
                    throw new UnsupportedTypeException(type);
                }
                return in.readByteString();

            case VARIANT:
                return decodeVariant(type, in, options);

            case DYNAMIC: {
                // Each value names its own type first, because a Dynamic column may hold a
                // different one in every row.
                ClickHouseType actual = BinaryTypeEncoding.read(in);
                if (actual.kind() == ClickHouseType.Kind.NOTHING) {
                    // Which is how Dynamic writes a NULL: the Nothing type and no value.
                    return null;
                }
                return decode(actual, in, options);
            }

            case SIMPLE_AGGREGATE_FUNCTION:
                // Only the "simple" kind: its state *is* a value of the argument type, so the
                // wire carries exactly that. A plain AggregateFunction state is an opaque blob
                // with its own layout per function and is refused below.
                return decode(type.arguments().get(0), in, options);

            case ARRAY: return decodeArray(type.arguments().get(0), in, options);
            case TUPLE:
            case NESTED: return decodeTuple(type, in, options);
            case MAP: return decodeMap(type, in, options);

            // Geometry is tuples and arrays of Float64 underneath, and the type name is the
            // only place the shape is written down, so it is expanded here. Decoded into
            // primitive double arrays rather than Object[]: clickhouse-jdbc returns double[],
            // double[][] and so on, and a caller that wants to do arithmetic on coordinates
            // should not have to unbox every one of them.
            case POINT: return decodePoint(in);
            case RING:
            case LINESTRING: return decodePointArray(in);
            case MULTILINESTRING:
            case POLYGON: return decodeRingArray(in);
            case MULTIPOLYGON: return decodePolygonArray(in);

            default:
                // Dynamic prefixes each value with ClickHouse's binary encoding of its type,
                // which is a recursive sub-format of its own; a plain AggregateFunction state is
                // an opaque per-function blob; and an unknown type has no reader by definition.
                // Refusing by name beats consuming the wrong number of bytes, which would
                // corrupt every column after this one rather than failing here.
                throw new UnsupportedTypeException(type);
        }
    }

    /**
     * A one-byte discriminator, then the chosen member's value.
     *
     * <p>The discriminator indexes the variant's members in sorted order, and the engine
     * normalises the type name into that same order — a column declared
     * {@code Variant(String, Int64)} is reported as {@code Variant(Int64, String)} — so the
     * parsed argument list can be indexed directly. 255 is the NULL discriminator.
     */
    private static Object decodeVariant(ClickHouseType type, RowBinaryInput in, Options options) {
        int discriminator = in.readUInt8();
        if (discriminator == 255) {
            return null;
        }
        List<ClickHouseType> members = type.arguments();
        if (discriminator >= members.size()) {
            throw new IllegalStateException(
                    "variant discriminator " + discriminator + " but " + type.name()
                            + " has only " + members.size() + " member(s)");
        }
        return decode(members.get(discriminator), in, options);
    }

    private static Object decodeDecimal(ClickHouseType type, RowBinaryInput in) {
        int width = type.precision() <= 9 ? 4 : type.precision() <= 18 ? 8 : type.precision() <= 38 ? 16 : 32;
        BigInteger unscaled = signedBigInteger(in.readBytes(width));
        return new BigDecimal(unscaled, type.scale());
    }

    /**
     * The wall clock the value reads as, in the column's zone.
     *
     * <p>Not the instant, deliberately. {@code DateTime} stores an instant, and reporting it as
     * one is defensible, but it is not what the reference driver does and not what a caller
     * expects: select a {@code DateTime('Asia/Shanghai')} holding 12:34:56 and you should see
     * 12:34:56, not the same moment rendered in whatever zone the JVM happens to run in. So the
     * instant is resolved in the column's declared zone -- or the engine's session zone when the
     * column does not declare one -- and the wall clock is what comes out. The zone itself stays
     * available on the type and in the options for a caller that asks for an
     * {@code OffsetDateTime}.
     */
    private static java.time.LocalDateTime localDateTime(
            Instant instant, ClickHouseType type, Options options) {
        ZoneId zone = type.timeZone() != null ? ZoneId.of(type.timeZone()) : options.sessionTimeZone();
        return java.time.LocalDateTime.ofInstant(instant, zone);
    }

    private static Instant decodeDateTime64Instant(ClickHouseType type, RowBinaryInput in) {
        long ticks = in.readInt64();
        int scale = type.scale();
        // floorDiv/floorMod rather than / and %, so a pre-epoch value keeps a positive
        // sub-second part instead of producing an Instant a second too late.
        long unitsPerSecond = pow10(scale);
        long seconds = Math.floorDiv(ticks, unitsPerSecond);
        long fraction = Math.floorMod(ticks, unitsPerSecond);
        long nanos = fraction * pow10(9 - scale);
        return Instant.ofEpochSecond(seconds, nanos);
    }


    private static Object decodeTime64(ClickHouseType type, RowBinaryInput in) {
        long ticks = in.readInt64();
        int scale = type.scale();
        long unitsPerSecond = pow10(scale);
        long seconds = Math.floorDiv(ticks, unitsPerSecond);
        long nanos = Math.floorMod(ticks, unitsPerSecond) * pow10(9 - scale);
        return LocalTime.ofNanoOfDay(Math.floorMod(seconds, 86400L) * 1_000_000_000L + nanos);
    }

    private static long pow10(int n) {
        long v = 1;
        for (int i = 0; i < n; i++) {
            v *= 10;
        }
        return v;
    }

    private static Object decodeUuid(RowBinaryInput in) {
        // Two little-endian 64-bit halves, most significant first.
        long high = in.readInt64();
        long low = in.readInt64();
        return new UUID(high, low);
    }

    private static Object decodeIpv4(RowBinaryInput in) {
        long v = in.readUInt32();
        byte[] octets = { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
        return address(octets);
    }

    private static Object decodeIpv6(RowBinaryInput in) {
        // Already in network order, unlike everything else in this format.
        return address(in.readBytes(16));
    }

    private static InetAddress address(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            // Only thrown for a length that is not 4 or 16, which cannot happen here.
            throw new IllegalStateException("address of " + raw.length + " bytes", e);
        }
    }

    private static double[] decodePoint(RowBinaryInput in) {
        return new double[] {
                Double.longBitsToDouble(in.readInt64()), Double.longBitsToDouble(in.readInt64()) };
    }

    private static double[][] decodePointArray(RowBinaryInput in) {
        int n = arrayLength(in);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            out[i] = decodePoint(in);
        }
        return out;
    }

    private static double[][][] decodeRingArray(RowBinaryInput in) {
        int n = arrayLength(in);
        double[][][] out = new double[n][][];
        for (int i = 0; i < n; i++) {
            out[i] = decodePointArray(in);
        }
        return out;
    }

    private static double[][][][] decodePolygonArray(RowBinaryInput in) {
        int n = arrayLength(in);
        double[][][][] out = new double[n][][][];
        for (int i = 0; i < n; i++) {
            out[i] = decodeRingArray(in);
        }
        return out;
    }

    private static int arrayLength(RowBinaryInput in) {
        long n = in.readVarUInt();
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new IllegalStateException("implausible element count " + n + " in RowBinary array");
        }
        return (int) n;
    }

    private static Object[] decodeArray(ClickHouseType element, RowBinaryInput in, Options options) {
        return decodeRepeated(element, in, options);
    }

    private static Object[] decodeRepeated(ClickHouseType element, RowBinaryInput in, Options options) {
        int n = arrayLength(in);
        List<Object> out = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            out.add(decode(element, in, options));
        }
        return out.toArray();
    }

    private static Object[] decodeTuple(ClickHouseType type, RowBinaryInput in, Options options) {
        List<ClickHouseType> args = type.arguments();
        Object[] out = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            out[i] = decode(args.get(i), in, options);
        }
        return out;
    }

    private static Map<Object, Object> decodeMap(ClickHouseType type, RowBinaryInput in, Options options) {
        ClickHouseType key = type.arguments().get(0);
        ClickHouseType value = type.arguments().get(1);
        int n = arrayLength(in);
        Map<Object, Object> out = new LinkedHashMap<>(Math.max(4, n * 2));
        for (int i = 0; i < n; i++) {
            Object k = decode(key, in, options);
            // A byte[] key would compare by identity and make the map useless to look up in.
            out.put(k instanceof byte[] ? new String((byte[]) k, StandardCharsets.UTF_8) : k,
                    decode(value, in, options));
        }
        return out;
    }

    /** Little-endian two's complement. */
    private static BigInteger signedBigInteger(byte[] littleEndian) {
        return new BigInteger(reversed(littleEndian));
    }

    /** Little-endian, unsigned. */
    private static BigInteger unsignedBigInteger(byte[] littleEndian) {
        return new BigInteger(1, reversed(littleEndian));
    }

    private static byte[] reversed(byte[] b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = b[b.length - 1 - i];
        }
        return out;
    }
}
