package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decoder against bytes the engine actually wrote.
 *
 * <p>Every vector below was captured from a real {@code RowBinaryWithNamesAndTypes} response
 * from engine v26.7.3, and every expectation was written from the SQL that produced it rather
 * than from what the decoder returns — an expectation copied from the code under test proves
 * only that it is consistent with itself.
 *
 * <p>Captured with {@code TZ=UTC}, which is what makes the temporal expectations unambiguous:
 * the engine reads its session timezone from the host, so the same SQL run elsewhere writes
 * different bytes.
 *
 * <p>Regenerate with {@code scripts/run-type-parity.sh}, which prints the vectors.
 */
class RowBinaryDecoderTest {

    /**
     * The vectors were captured with output_format_binary_write_json_as_string on, which is
     * what the driver asks for, so the decoder is exercised the same way it will run.
     */
    private static final RowBinaryDecoder.Options OPTIONS =
            new RowBinaryDecoder.Options(true, java.time.ZoneOffset.UTC);

    /** name -> {type name, hex of the value bytes, header excluded}. */
    private static final Map<String, String[]> VECTORS = new LinkedHashMap<>();

    static {
        VECTORS.put("int128Max", new String[] {"Int128", "ffffffffffffffffffffffffffffff7f"});
        VECTORS.put("int128Min", new String[] {"Int128", "00000000000000000000000000000080"});
        VECTORS.put("uint64Max", new String[] {"UInt64", "ffffffffffffffff"});
        VECTORS.put("uint8Max", new String[] {"UInt8", "ff"});
        VECTORS.put("int8Min", new String[] {"Int8", "80"});
        VECTORS.put("float64Nan", new String[] {"Float64", "000000000000f87f"});
        VECTORS.put("bfloat16", new String[] {"BFloat16", "c03f"});
        VECTORS.put("decimal9s4", new String[] {"Decimal(9, 4)", "4e61bc00"});
        VECTORS.put("decimal76s40", new String[] {"Decimal(76, 40)", "010000000061f5b9abbfa45cc3f129631d000000000000000000000000000000"});
        VECTORS.put("decimalNegative", new String[] {"Decimal(18, 2)", "ceffffffffffffff"});
        VECTORS.put("enumNegative", new String[] {"Enum8('neg' = -5, '' = 0)", "fb"});
        VECTORS.put("enumZero", new String[] {"Enum8('' = 0, 'a' = 1)", "00"});
        VECTORS.put("enum16Big", new String[] {"Enum16('zero' = 0, 'big' = 30000)", "3075"});
        VECTORS.put("fixedStringPadded", new String[] {"FixedString(5)", "6162000000"});
        VECTORS.put("stringInvalidUtf8", new String[] {"String", "10a3a312a0df134e8c8774d453dbfc3495"});
        VECTORS.put("uuid", new String[] {"UUID", "e711b35c04c4f061a0dbd36a00a67b90"});
        VECTORS.put("ipv4", new String[] {"IPv4", "0101a8c0"});
        VECTORS.put("ipv6", new String[] {"IPv6", "20010db8000000000000000000000001"});
        VECTORS.put("ipv6Mapped", new String[] {"IPv6", "00000000000000000000ffffc0a80101"});
        VECTORS.put("dateEpoch", new String[] {"Date", "0000"});
        VECTORS.put("date32PreEpoch", new String[] {"Date32", "ccbfffff"});
        VECTORS.put("dateTime", new String[] {"DateTime", "70deab6a"});
        VECTORS.put("dateTime64Nanos", new String[] {"DateTime64(9)", "79bfeb6bd31bd618"});
        VECTORS.put("dateTime64PreEpoch", new String[] {"DateTime64(3)", "f435a183b6ffffff"});
        VECTORS.put("time64", new String[] {"Time64(3)", "952cb30200000000"});
        VECTORS.put("nullableNull", new String[] {"Nullable(Int32)", "01"});
        VECTORS.put("nullableValue", new String[] {"Nullable(Int32)", "0007000000"});
        VECTORS.put("lowCardNullableNull", new String[] {"LowCardinality(Nullable(String))", "01"});
        VECTORS.put("arrayNullable", new String[] {"Array(Nullable(UInt8))", "030001010003"});
        VECTORS.put("arrayOfArray", new String[] {"Array(Array(UInt8))", "020201020103"});
        VECTORS.put("tupleNamed", new String[] {"Tuple(n Int32, s String)", "010000000161"});
        VECTORS.put("map", new String[] {"Map(String, UInt8)", "02016101016202"});
        VECTORS.put("point", new String[] {"Point", "000000000000f03f0000000000000040"});
        VECTORS.put("ring", new String[] {"Ring", "0200000000000000000000000000000000000000000000f03f0000000000000000"});
        VECTORS.put("simpleAggregate", new String[] {"SimpleAggregateFunction(sum, Int64)", "0100000000000000"});
        VECTORS.put("interval", new String[] {"IntervalDay", "0300000000000000"});
        VECTORS.put("jsonAsString", new String[] {"JSON", "0f7b2261223a312c2262223a2278227d"});
        VECTORS.put("variantInt", new String[] {"Variant(Int64, String)", "002a00000000000000"});
        VECTORS.put("variantString", new String[] {"Variant(Int64, String)", "010178"});
        VECTORS.put("variantNull", new String[] {"Variant(Int64, String)", "ff"});
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Decodes a captured vector, and insists the decoder consumed exactly its bytes. */
    private static Object decode(String name) {
        String[] v = VECTORS.get(name);
        if (v == null) {
            throw new IllegalArgumentException("no vector named " + name);
        }
        ClickHouseType type = ClickHouseType.parse(v[0]);
        RowBinaryInput in = new RowBinaryInput(hex(v[1]));
        Object decoded = RowBinaryDecoder.decode(type, in, OPTIONS);
        // Leftover bytes mean the next column of a real row would be read from the wrong
        // offset, which corrupts everything after it rather than failing here.
        assertEquals(0, in.remaining(), name + ": " + in.remaining() + " byte(s) not consumed");
        return decoded;
    }

    @Test
    @DisplayName("every captured vector decodes to the value its SQL asked for")
    void vectors() {
        assertEquals(new BigInteger("170141183460469231731687303715884105727"), decode("int128Max"));
        assertEquals(new BigInteger("-170141183460469231731687303715884105728"), decode("int128Min"));
        assertEquals(new BigInteger("18446744073709551615"), decode("uint64Max"));
        assertEquals((short) 255, decode("uint8Max"));
        assertEquals((byte) -128, decode("int8Min"));
        assertTrue(Double.isNaN((Double) decode("float64Nan")));
        assertEquals(1.5f, decode("bfloat16"));
        assertEquals(new BigDecimal("1234.5678"), decode("decimal9s4"));
        assertEquals(new BigDecimal("1.0000000000000000000000000000000000000001"), decode("decimal76s40"));
        assertEquals(new BigDecimal("-0.50"), decode("decimalNegative"));
        assertEquals((byte) -5, decode("enumNegative"));
        assertEquals((byte) 0, decode("enumZero"));
        assertEquals((short) 30000, decode("enum16Big"));
        assertArrayEquals(new byte[] {'a', 'b', 0, 0, 0}, (byte[]) decode("fixedStringPadded"));
        assertArrayEquals(hex("a3a312a0df134e8c8774d453dbfc3495"), (byte[]) decode("stringInvalidUtf8"));
        assertEquals(UUID.fromString("61f0c404-5cb3-11e7-907b-a6006ad3dba0"), decode("uuid"));
        assertEquals("192.168.1.1", ((InetAddress) decode("ipv4")).getHostAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", ((InetAddress) decode("ipv6")).getHostAddress());
        assertEquals("192.168.1.1", ((InetAddress) decode("ipv6Mapped")).getHostAddress());
        assertEquals(LocalDate.parse("1970-01-01"), decode("dateEpoch"));
        assertEquals(LocalDate.parse("1925-01-01"), decode("date32PreEpoch"));
        assertEquals(LocalDateTime.parse("2026-09-17T12:34:56"), decode("dateTime"));
        assertEquals(LocalDateTime.parse("2026-09-17T12:34:56.789012345"), decode("dateTime64Nanos"));
        assertEquals(LocalDateTime.parse("1960-01-01T00:00:00.500"), decode("dateTime64PreEpoch"));
        assertEquals(LocalTime.parse("12:34:56.789"), decode("time64"));
        assertNull(decode("nullableNull"));
        assertEquals(7, decode("nullableValue"));
        assertNull(decode("lowCardNullableNull"));
        assertArrayEquals(new Object[] {(short) 1, null, (short) 3}, (Object[]) decode("arrayNullable"));
        assertEquals("[[1, 2], [3]]", java.util.Arrays.deepToString((Object[]) decode("arrayOfArray")));
        assertEquals("a", new String((byte[]) ((Object[]) decode("tupleNamed"))[1], java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("{a=1, b=2}", decode("map").toString());
        assertArrayEquals(new double[] {1.0, 2.0}, (double[]) decode("point"));
        assertEquals("[[0.0, 0.0], [1.0, 0.0]]", java.util.Arrays.deepToString((double[][]) decode("ring")));
        assertEquals(1L, decode("simpleAggregate"));
        assertEquals(3L, decode("interval"));
        assertEquals("{\"a\":1,\"b\":\"x\"}",
                new String((byte[]) decode("jsonAsString"), java.nio.charset.StandardCharsets.UTF_8));
        // The discriminator indexes the members in the order the engine reports them, which it
        // normalises: a column declared Variant(String, Int64) comes back Variant(Int64, String).
        assertEquals(42L, decode("variantInt"));
        assertEquals("x", new String((byte[]) decode("variantString"), java.nio.charset.StandardCharsets.UTF_8));
        assertNull(decode("variantNull"));
    }

    @Test
    @DisplayName("a type with no reader is refused by name, not by running off the end")
    void unsupportedTypesAreNamed() {
        // What is left now that Dynamic has a reader: an AggregateFunction state, which is an
        // opaque per-function blob, and anything the parser could not classify. Both are
        // refused by name, because consuming the wrong number of bytes would corrupt the
        // columns after this one rather than failing where the problem is.
        for (String name : new String[] {
            "AggregateFunction(quantiles(0.5), UInt64)", "SomeTypeFrom2030(Int32)"
        }) {
            ClickHouseType t = ClickHouseType.parse(name);
            RowBinaryDecoder.UnsupportedTypeException e =
                    assertThrows(
                            RowBinaryDecoder.UnsupportedTypeException.class,
                            () -> RowBinaryDecoder.decode(t, new RowBinaryInput(new byte[64]), OPTIONS));
            assertEquals(name, e.type().name());
        }
    }

    @Test
    @DisplayName("a DateTime reads as the wall clock in its own zone, not in the JVM's")
    void dateTimeUsesTheColumnsZone() {
        // The decision this pins: select a DateTime('Asia/Shanghai') holding 12:34:56 and you
        // get 12:34:56, whatever zone the JVM runs in. The alternative -- reporting the instant
        // -- is defensible but disagrees with clickhouse-jdbc and surprises callers.
        //
        // 0x6aabde70 little-endian is 1789648496, which is 12:34:56 UTC on 2026-09-17.
        byte[] bytes = hex("70deab6a");

        assertEquals(
                LocalDateTime.parse("2026-09-17T12:34:56"),
                RowBinaryDecoder.decode(
                        ClickHouseType.parse("DateTime('UTC')"), new RowBinaryInput(bytes), OPTIONS));

        // Same instant, a column declared eight hours ahead: the wall clock moves, the bytes
        // do not.
        assertEquals(
                LocalDateTime.parse("2026-09-17T20:34:56"),
                RowBinaryDecoder.decode(
                        ClickHouseType.parse("DateTime('Asia/Shanghai')"),
                        new RowBinaryInput(bytes),
                        OPTIONS));

        // No declared zone, so the engine's session zone decides.
        assertEquals(
                LocalDateTime.parse("2026-09-18T00:34:56"),
                RowBinaryDecoder.decode(
                        ClickHouseType.parse("DateTime"),
                        new RowBinaryInput(bytes),
                        new RowBinaryDecoder.Options(true, java.time.ZoneId.of("Pacific/Auckland"))));
    }

    @Test
    @DisplayName("JSON is refused rather than guessed at when the engine was not told to write it as a string")
    void jsonNeedsTheSettingItWasCapturedWith() {
        // The header says JSON whether or not output_format_binary_write_json_as_string was
        // set, so the bytes alone cannot say which encoding arrived, and reading one as the
        // other returns plausible rubbish. The setting travels with the bytes for that reason,
        // and these are the same bytes read both ways.
        ClickHouseType json = ClickHouseType.parse("JSON");

        // The string form, which the driver no longer asks for.
        byte[] asString = hex("0f7b2261223a312c2262223a2278227d");
        Object text = RowBinaryDecoder.decode(
                json, new RowBinaryInput(asString), new RowBinaryDecoder.Options(true, java.time.ZoneOffset.UTC));
        assertEquals(
                "{\"a\":1,\"b\":\"x\"}",
                new String((byte[]) text, java.nio.charset.StandardCharsets.UTF_8));

        // The paths form, which it does: two paths, "d" a String and "a.b.c" an Int64.
        // Captured from the engine -- see docs/type-parity-clickhouse-jdbc.md.
        byte[] asPaths = hex("02016415017805612e622e630a0700000000000000");
        Object decoded = RowBinaryDecoder.decode(
                json, new RowBinaryInput(asPaths), RowBinaryDecoder.STRICT);
        java.util.Map<?, ?> paths = (java.util.Map<?, ?>) decoded;
        assertEquals(2, paths.size());
        assertEquals(7L, paths.get("a.b.c"));
        assertEquals("x", new String((byte[]) paths.get("d"), java.nio.charset.StandardCharsets.UTF_8));

        // Read the paths form as a string and it is not an error, just nonsense -- which is
        // why the setting is pinned rather than guessed at.
        assertEquals(
                2,
                ((byte[]) RowBinaryDecoder.decode(
                        json,
                        new RowBinaryInput(asPaths),
                        new RowBinaryDecoder.Options(true, java.time.ZoneOffset.UTC))).length);
    }

    @Test
    @DisplayName("a JSON path declared in the type carries no type tag; an undeclared one does")
    void jsonTypedPathsAreReadByTheirDeclaredType() {
        // JSON(a UInt32) holding {"a":1,"b":2}. Captured from the engine: "a" is four bytes
        // with no tag, because the type already said UInt32; "b" is a Dynamic, so it names
        // Int64 first.
        ClickHouseType json = ClickHouseType.parse("JSON(a UInt32)");
        byte[] bytes = hex("0201610100000001620a0200000000000000");
        Map<?, ?> paths = (Map<?, ?>) RowBinaryDecoder.decode(
                json, new RowBinaryInput(bytes), RowBinaryDecoder.STRICT);
        assertEquals(2, paths.size());
        assertEquals(1L, paths.get("a"));
        assertEquals(2L, paths.get("b"));
    }

    @Test
    @DisplayName("a JSON nested inside a Dynamic names itself, parameters and all")
    void jsonInsideADynamicNamesItself() {
        // {"a":{"b":[1,"2",{"c":7}]}} -- one path a.b holding an Array(Dynamic) whose third
        // element is itself a JSON. That element is the only place a JSON value has to carry
        // its own type tag, so it is the only thing that exercises the 0x30 reader.
        ClickHouseType json = ClickHouseType.parse("JSON");
        byte[] bytes = hex("0103612e621e2b20030a010000000000000015013230008008200000000101630a0700000000000000");
        Map<?, ?> paths = (Map<?, ?>) RowBinaryDecoder.decode(
                json, new RowBinaryInput(bytes), RowBinaryDecoder.STRICT);
        assertEquals(1, paths.size());
        Object[] elements = (Object[]) paths.get("a.b");
        assertEquals(3, elements.length);
        assertEquals(1L, elements[0]);
        assertEquals("2", new String((byte[]) elements[1], java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(7L, ((Map<?, ?>) elements[2]).get("c"));
    }

    @Test
    @DisplayName("a truncated value fails where it happens instead of reading past the end")
    void truncated() {
        ClickHouseType i64 = ClickHouseType.parse("Int64");
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> RowBinaryDecoder.decode(i64, new RowBinaryInput(new byte[] {1, 2, 3}), OPTIONS));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
    }

    @Test
    @DisplayName("a length that could not fit the buffer is rejected, not trusted")
    void implausibleLength() {
        // A varint length is a 64-bit value in the format. Believing one would mean allocating
        // or indexing on a number that came off the wire.
        ClickHouseType str = ClickHouseType.parse("String");
        assertThrows(
                IllegalStateException.class,
                () -> RowBinaryDecoder.decode(
                        str, new RowBinaryInput(new byte[] {(byte) 0xff, (byte) 0xff, 0x7f}), OPTIONS));
    }
}
