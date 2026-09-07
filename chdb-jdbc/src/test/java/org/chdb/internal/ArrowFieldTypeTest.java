package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Arrow format string is the driver's only input for a column's type, so parsing it wrongly
 * means reading the buffers wrongly. This pins the V1 type matrix (work plan section 5.9)
 * against the format strings chDB actually emits.
 */
class ArrowFieldTypeTest {

    private static ArrowFieldType parse(String format) {
        return ArrowFieldType.parse(format, false, false);
    }

    @ParameterizedTest
    @DisplayName("primitive format strings map to the documented kind and width")
    @CsvSource({
        "b,   BOOL,   0",
        "c,   INT8,   1",
        "C,   UINT8,  1",
        "s,   INT16,  2",
        "S,   UINT16, 2",
        "i,   INT32,  4",
        "I,   UINT32, 4",
        "l,   INT64,  8",
        "L,   UINT64, 8",
        "e,   FLOAT16, 2",
        "f,   FLOAT32, 4",
        "g,   FLOAT64, 8",
        "u,   UTF8,   0",
        "U,   LARGE_UTF8, 0",
        "z,   BINARY, 0",
        "Z,   LARGE_BINARY, 0",
        "tdD, DATE32, 4",
        "tdm, DATE64, 8",
    })
    void primitives(String format, String kind, int byteWidth) {
        ArrowFieldType type = parse(format);
        assertEquals(ArrowFieldType.Kind.valueOf(kind), type.kind(), format);
        assertEquals(byteWidth, type.byteWidth(), format);
        assertTrue(type.supported(), format);
    }

    @Test
    @DisplayName("decimal carries precision, scale and storage width")
    void decimal() {
        ArrowFieldType d128 = parse("d:18,3");
        assertEquals(ArrowFieldType.Kind.DECIMAL, d128.kind());
        assertEquals(18, d128.precision());
        assertEquals(3, d128.scale());
        assertEquals(16, d128.byteWidth());
        assertEquals(BigDecimal.class, d128.javaClass());
        assertEquals("Decimal(18, 3)", d128.typeName());

        ArrowFieldType d256 = parse("d:76,10,256");
        assertEquals(32, d256.byteWidth());

        assertEquals(16, parse("d:9,2,128").byteWidth());
    }

    @Test
    @DisplayName("a decimal with a width chDB never emits is unsupported, not mis-sliced")
    void oddDecimalWidth() {
        assertFalse(parse("d:9,2,64").supported());
        assertFalse(parse("d:9").supported());
    }

    @Test
    @DisplayName("fixed-size binary of 16 bytes is a UUID, other widths are byte arrays")
    void fixedSizeBinary() {
        ArrowFieldType uuid = parse("w:16");
        assertEquals(16, uuid.byteWidth());
        assertEquals(UUID.class, uuid.javaClass());
        assertEquals("UUID", uuid.typeName());

        ArrowFieldType fixed = parse("w:3");
        assertEquals(byte[].class, fixed.javaClass());
        assertEquals("FixedString(3)", fixed.typeName());

        assertFalse(parse("w:0").supported());
        assertFalse(parse("w:abc").supported());
    }

    @Test
    @DisplayName("timestamp units and timezones are read off the format string")
    void timestamps() {
        ArrowFieldType utc = parse("tsu:UTC");
        assertEquals(ArrowFieldType.Kind.TIMESTAMP, utc.kind());
        assertEquals(ArrowFieldType.TimeUnit.MICRO, utc.unit());
        assertEquals("UTC", utc.timezone());
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, utc.jdbcType());
        assertEquals(Instant.class, utc.javaClass());

        ArrowFieldType zoned = parse("tsm:Europe/Berlin");
        assertEquals(ArrowFieldType.TimeUnit.MILLI, zoned.unit());
        assertEquals("Europe/Berlin", zoned.timezone());

        // A timestamp with no zone: "tsu:" with nothing after the colon.
        ArrowFieldType naive = parse("tsu:");
        assertEquals(ArrowFieldType.TimeUnit.MICRO, naive.unit());
        assertEquals(null, naive.timezone());
        assertEquals(Types.TIMESTAMP, naive.jdbcType());

        assertEquals(ArrowFieldType.TimeUnit.SECOND, parse("tss:UTC").unit());
        assertEquals(ArrowFieldType.TimeUnit.NANO, parse("tsn:UTC").unit());
        assertFalse(parse("tsx:UTC").supported());
    }

    @Test
    @DisplayName("time widths follow the unit")
    void times() {
        assertEquals(4, parse("tts").byteWidth());
        assertEquals(4, parse("ttm").byteWidth());
        assertEquals(8, parse("ttu").byteWidth());
        assertEquals(8, parse("ttn").byteWidth());
        assertEquals(ArrowFieldType.Kind.TIME32, parse("tts").kind());
        assertEquals(ArrowFieldType.Kind.TIME64, parse("ttn").kind());
    }

    @ParameterizedTest
    @DisplayName("nested, dictionary-encoded and unknown formats are UNSUPPORTED, never guessed")
    @ValueSource(strings = {"+l", "+L", "+s", "+m", "+ud:0,1", "+r", "vu", "", "qqq", "tiM"})
    void unsupportedFormats(String format) {
        ArrowFieldType type = parse(format);
        assertFalse(type.supported(), format);
        assertEquals(Types.OTHER, type.jdbcType(), format);
        assertTrue(type.typeName().startsWith("Unsupported(arrow="), type.typeName());
    }

    @Test
    @DisplayName("a dictionary-encoded column is unsupported even when its value format is readable")
    void dictionaryEncodedIsUnsupported() {
        // LowCardinality(String) emits "u" for the values, so only the schema's dictionary
        // pointer distinguishes it -- the format string alone cannot.
        ArrowFieldType plain = ArrowFieldType.parse("u", true, false);
        assertTrue(plain.supported());
        ArrowFieldType dictionary = ArrowFieldType.parse("u", true, true);
        assertFalse(dictionary.supported());
        assertTrue(dictionary.typeName().contains("dictionary"), dictionary.typeName());
    }

    @Test
    @DisplayName("UInt64 is NUMERIC/BigInteger, because no JDBC integer type holds it")
    void unsignedLongMapping() {
        ArrowFieldType type = parse("L");
        assertEquals(Types.NUMERIC, type.jdbcType());
        assertEquals(BigInteger.class, type.javaClass());
        assertFalse(type.signed());
        assertEquals(20, type.jdbcPrecision());
    }

    @Test
    @DisplayName("unsigned integers widen to the next Java type that holds them")
    void unsignedWidening() {
        assertEquals(Types.SMALLINT, parse("C").jdbcType());
        assertEquals(Short.class, parse("C").javaClass());
        assertEquals(Types.INTEGER, parse("S").jdbcType());
        assertEquals(Integer.class, parse("S").javaClass());
        assertEquals(Types.BIGINT, parse("I").jdbcType());
        assertEquals(Long.class, parse("I").javaClass());
    }

    @Test
    @DisplayName("dates map to LocalDate and JDBC DATE")
    void dates() {
        assertEquals(LocalDate.class, parse("tdD").javaClass());
        assertEquals(Types.DATE, parse("tdD").jdbcType());
        assertEquals(10, parse("tdD").displaySize());
    }

    @Test
    @DisplayName("the nullable flag is carried through, since it drives ResultSetMetaData")
    void nullability() {
        assertTrue(ArrowFieldType.parse("i", true, false).nullable());
        assertFalse(ArrowFieldType.parse("i", false, false).nullable());
    }

    @Test
    @DisplayName("a null format string is unsupported rather than a NullPointerException")
    void nullFormat() {
        assertFalse(ArrowFieldType.parse(null, false, false).supported());
    }
}
