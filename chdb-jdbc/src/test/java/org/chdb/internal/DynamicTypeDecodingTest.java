package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Dynamic}, and with it ClickHouse's binary encoding of a data type.
 *
 * <p>A Dynamic value names its own type before its bytes, so reading one means reading that
 * encoding — specified in the engine's {@code DataTypesBinaryEncoding.h}. Every vector here was
 * captured from a real response, and between them they exercise each tag the reader implements:
 * the plain scalars, a parameterised one, the two that recurse, a named tuple, a map, an
 * interval kind, and the custom-type escape hatch that {@code Point} arrives through.
 */
class DynamicTypeDecodingTest {

    private static final RowBinaryDecoder.Options OPTIONS =
            new RowBinaryDecoder.Options(true, ZoneOffset.UTC);

    private static final Map<String, String> DYNAMIC = new LinkedHashMap<>();

    static {
        DYNAMIC.put("dynInt64", "0a2a00000000000000");
        DYNAMIC.put("dynString", "150173");
        DYNAMIC.put("dynNull", "00");
        DYNAMIC.put("dynDate", "0fe950");
        DYNAMIC.put("dynDecimal", "1a12027d00000000000000");
        DYNAMIC.put("dynArray", "1e09020100000002000000");
        DYNAMIC.put("dynEnum", "170201610601620707");
        DYNAMIC.put("dynDateTime64Tz", "14030355544395e85cafa0010000");
        DYNAMIC.put("dynTupleNamed", "2002016e09017315010000000161");
        DYNAMIC.put("dynMap", "27150901016101000000");
        DYNAMIC.put("dynPoint", "2c05506f696e74000000000000f03f0000000000000040");
        DYNAMIC.put("dynInterval", "22060300000000000000");
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String text(Object v) {
        return new String((byte[]) v, StandardCharsets.UTF_8);
    }

    private static Object decode(String name) {
        RowBinaryInput in = new RowBinaryInput(hex(DYNAMIC.get(name)));
        Object v = RowBinaryDecoder.decode(ClickHouseType.parse("Dynamic"), in, OPTIONS);
        assertEquals(0, in.remaining(), name + ": " + in.remaining() + " byte(s) not consumed");
        return v;
    }

    @Test
    @DisplayName("a Dynamic value decodes as whatever type it says it is")
    void dynamicValues() {
        // Int64
        assertEquals(42L, decode("dynInt64"));
        // String
        assertEquals("s", text(decode("dynString")));
        // Nothing, which is how a Dynamic NULL is written
        assertNull(decode("dynNull"));
        // Date
        assertEquals(LocalDate.parse("2026-09-17"), decode("dynDate"));
        // Decimal64, whose precision and scale ride in the encoding
        assertEquals(new BigDecimal("1.25"), decode("dynDecimal"));
        // Array(Int32), recursing into the element's encoding
        assertArrayEquals(new Object[] {1, 2}, (Object[]) decode("dynArray"));
        // Enum8 with its members, so the label survives
        assertEquals((byte) 7, decode("dynEnum"));
        // DateTime64(3, 'UTC')
        assertEquals(LocalDateTime.parse("2026-09-17T12:34:56.789"), decode("dynDateTime64Tz"));
        // a named Tuple
        assertEquals("a", text(((Object[]) decode("dynTupleNamed"))[1]));
        // Map(String, Int32)
        assertEquals("{a=1}", decode("dynMap").toString());
        // Point, which arrives as a named custom type
        assertArrayEquals(new double[] {1.0, 2.0}, (double[]) decode("dynPoint"));
        // IntervalDay, whose kind is a second byte
        assertEquals(3L, decode("dynInterval"));
    }

    @Test
    @DisplayName("an unimplemented type tag is refused rather than guessed")
    void unknownTagRefused() {
        // 0x21 is Set, which has no reader. Returning an approximate type would decode the
        // bytes after it as the wrong thing, which corrupts the rest of the row instead of
        // failing here.
        assertThrows(
                BinaryTypeEncoding.UnsupportedEncodingException.class,
                () -> RowBinaryDecoder.decode(
                        ClickHouseType.parse("Dynamic"),
                        new RowBinaryInput(hex("21")),
                        OPTIONS));
    }

    @Test
    @DisplayName("IntervalYear is 0x1A, not the 0x0A the sequence suggests")
    void intervalYearTagIsNotSequential() {
        // The engine's own table jumps from Quarter at 0x09 to Year at 0x1A. Reading the
        // sequence instead of the table gives IntervalYear the wrong tag, and nothing about the
        // resulting value looks wrong.
        RowBinaryInput in = new RowBinaryInput(hex("221a0700000000000000"));
        assertEquals(7L, RowBinaryDecoder.decode(ClickHouseType.parse("Dynamic"), in, OPTIONS));
        assertEquals(0, in.remaining());
    }
}
