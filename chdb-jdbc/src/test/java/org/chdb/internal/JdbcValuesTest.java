package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a caller sees, checked against what clickhouse-jdbc shows them.
 *
 * <p>Each row is a byte vector captured from the engine and the {@code getString} that
 * clickhouse-jdbc returns for the same expression. Between them they pin the renderings that
 * look like accidents in isolation: an enum shows its label, a {@code FixedString} keeps its NUL
 * padding, an {@code IPv6} shows the expanded form, a {@code Date} shows ISO, a timestamp shows
 * no fractional digits when its scale is zero, a string element inside an array is quoted where
 * a top-level string is not, a null element is uppercase {@code NULL}, and a {@code Point} has
 * no space after its comma.
 *
 * <p>Needs no engine: the bytes and the expectations are both recorded.
 */
class JdbcValuesTest {

    private static final RowBinaryDecoder.Options OPTIONS =
            new RowBinaryDecoder.Options(true, ZoneOffset.UTC);

    private static final class Case {
        final String name;
        final String typeName;
        final String hex;
        final String referenceString;

        Case(String name, String typeName, String hex, String referenceString) {
            this.name = name;
            this.typeName = typeName;
            this.hex = hex;
            this.referenceString = referenceString;
        }
    }

    private static Case row(String n, String t, String h, String s) {
        return new Case(n, t, h, s);
    }

    private static final List<Case> CASES = Arrays.asList(
        row("int128Max", "Int128", "ffffffffffffffffffffffffffffff7f", "170141183460469231731687303715884105727"),
        row("uint64Max", "UInt64", "ffffffffffffffff", "18446744073709551615"),
        row("uint8Max", "UInt8", "ff", "255"),
        row("int8Min", "Int8", "80", "-128"),
        row("decimal9s4", "Decimal(9, 4)", "4e61bc00", "1234.5678"),
        row("decimalNegative", "Decimal(18, 2)", "ceffffffffffffff", "-0.50"),
        row("enumNegative", "Enum8('neg' = -5, '' = 0)", "fb", "neg"),
        row("enumZero", "Enum8('' = 0, 'a' = 1)", "00", ""),
        row("enum16Big", "Enum16('zero' = 0, 'big' = 30000)", "3075", "big"),
        row("fixedStringPadded", "FixedString(5)", "6162000000", "ab\0\0\0"),
        row("uuid", "UUID", "e711b35c04c4f061a0dbd36a00a67b90", "61f0c404-5cb3-11e7-907b-a6006ad3dba0"),
        row("ipv4", "IPv4", "0101a8c0", "192.168.1.1"),
        row("ipv6", "IPv6", "20010db8000000000000000000000001", "2001:db8:0:0:0:0:0:1"),
        row("dateEpoch", "Date", "0000", "1970-01-01"),
        row("date32PreEpoch", "Date32", "ccbfffff", "1925-01-01"),
        row("dateTime", "DateTime", "70deab6a", "2026-09-17 12:34:56"),
        row("dateTime64Nanos", "DateTime64(9)", "79bfeb6bd31bd618", "2026-09-17 12:34:56.789012345"),
        row("time64", "Time64(3)", "952cb30200000000", "12:34:56.789"),
        row("nullableNull", "Nullable(Int32)", "01", "null"),
        row("nullableValue", "Nullable(Int32)", "0007000000", "7"),
        row("arrayNullable", "Array(Nullable(UInt8))", "030001010003", "[1, NULL, 3]"),
        row("arrayOfArray", "Array(Array(UInt8))", "020201020103", "[[1, 2], [3]]"),
        row("map", "Map(String, UInt8)", "02016101016202", "{a=1, b=2}"),
        row("point", "Point", "000000000000f03f0000000000000040", "(1.0,2.0)"),
        // Two points, not the three the parity matrix uses: this vector came from
        // CAST([(0.0,0.0),(1.0,0.0)] AS Ring), so the expectation is that SQL's and not the
        // recorded reference string for a different expression.
        row("ring", "Ring", "0200000000000000000000000000000000000000000000f03f0000000000000000", "[(0.0,0.0),(1.0,0.0)]"));

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    @DisplayName("getString matches clickhouse-jdbc for every recorded case")
    void getStringMatchesTheReference() throws Exception {
        for (Case c : CASES) {
            ClickHouseType type = ClickHouseType.parse(c.typeName);
            Object decoded =
                    RowBinaryDecoder.decode(type, new RowBinaryInput(hex(c.hex)), OPTIONS);
            JdbcValues.Column col = new JdbcValues.Column(1, "v", type);
            assertEquals(
                    c.referenceString,
                    String.valueOf(JdbcValues.asString(col, decoded)),
                    c.name + " (" + c.typeName + ")");
        }
    }

    @Test
    @DisplayName("an out-of-range value is refused rather than saturated")
    void integralOverflowThrows() throws Exception {
        // The old Arrow path answered getLong on a Float64 with Long.MAX_VALUE, which is a
        // wrong number presented as a right one. A number that will not fit is an error.
        ClickHouseType f64 = ClickHouseType.parse("Float64");
        JdbcValues.Column col = new JdbcValues.Column(1, "v", f64);
        Object huge =
                RowBinaryDecoder.decode(f64, new RowBinaryInput(hex("ffffffffffffef7f")), OPTIONS);

        java.sql.SQLException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.sql.SQLDataException.class, () -> JdbcValues.asLong(col, huge));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("does not fit"), e.getMessage());

        // In range, the fraction is truncated rather than the call refused: JDBC permits the
        // conversion, and refusing a legal one is its own kind of wrong.
        Object small = RowBinaryDecoder.decode(
                f64, new RowBinaryInput(hex("3d0ad7a3703d0a40")), OPTIONS);
        assertEquals(3L, JdbcValues.asLong(col, small));
    }

    @Test
    @DisplayName("getBytes is refused for a column that is not binary")
    void getBytesOnANumberIsRefused() {
        // The old path returned the UTF-8 of a number's decimal text, which makes an error
        // look like data.
        ClickHouseType i32 = ClickHouseType.parse("Int32");
        JdbcValues.Column col = new JdbcValues.Column(1, "v", i32);
        java.sql.SQLException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.sql.SQLException.class, () -> JdbcValues.asBytes(col, 7));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("getBytes"), e.getMessage());
    }

    @Test
    @DisplayName("getBigDecimal refuses infinity and NaN with a SQLException, not an unchecked one")
    void bigDecimalHasNoInfinity() throws Exception {
        // Found by the differential run: both drivers refuse these, and only we did it by
        // letting a NumberFormatException out of a JDBC accessor, thrown from inside the JDK.
        ClickHouseType f64 = ClickHouseType.parse("Float64");
        JdbcValues.Column col = new JdbcValues.Column(1, "v", f64);
        String[] vectors = {"000000000000f07f", "000000000000f0ff", "000000000000f87f"};
        for (String hex : vectors) {
            Object value = RowBinaryDecoder.decode(f64, new RowBinaryInput(hex(hex)), OPTIONS);
            java.sql.SQLException e =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.sql.SQLDataException.class,
                            () -> JdbcValues.asBigDecimal(col, value),
                            hex);
            assertEquals("22003", e.getSQLState(), hex);
        }
        // getDouble still answers them: the value exists, it just has no BigDecimal.
        Object inf = RowBinaryDecoder.decode(
                f64, new RowBinaryInput(hex("000000000000f07f")), OPTIONS);
        assertEquals(Double.POSITIVE_INFINITY, JdbcValues.asDouble(col, inf));
    }

    @Test
    @DisplayName("a Float32 reads the same BigDecimal on every JDK, which is why it is not the float's own text")
    void float32BigDecimalIsTheSameOnEveryJdk() throws Exception {
        // The two strings are the point, and CI running this on Java 11, 17, 21, 25 and 26 is
        // what makes it an assertion. Matching clickhouse-jdbc's shorter
        // new BigDecimal(Float.toString(f)) was tried and reverted: Float.toString changed
        // algorithm in JDK 19, so it renders MIN_NORMAL as 1.17549435E-38 on Java 11 and 17
        // and 1.1754944E-38 later. Answering differently per JVM is the worse defect.
        ClickHouseType f32 = ClickHouseType.parse("Float32");
        JdbcValues.Column col = new JdbcValues.Column(1, "v", f32);

        Object max = RowBinaryDecoder.decode(f32, new RowBinaryInput(hex("ffff7f7f")), OPTIONS);
        assertEquals("3.4028234663852886E+38", JdbcValues.asBigDecimal(col, max).toString());

        Object tiny = RowBinaryDecoder.decode(f32, new RowBinaryInput(hex("00008000")), OPTIONS);
        assertEquals("1.1754943508222875E-38", JdbcValues.asBigDecimal(col, tiny).toString());

        // And it is the float's value, not a different number: it round-trips.
        assertEquals(Float.MIN_NORMAL, JdbcValues.asBigDecimal(col, tiny).floatValue());
    }

    @Test
    @DisplayName("getBytes on an address follows the reference; getBoolean on NaN does not")
    void followsTheReferenceOnlyWhereItIsConsidered() throws Exception {
        // NaN stays true here: not zero, and JDBC's rule is "zero is false". The reference says
        // false, but its convertToBoolean is longValue() != 0, so 0.5 and 1.17e-38 are false
        // there too while the same 0.5 as a Decimal is true. That is a bug to not copy.
        ClickHouseType f64 = ClickHouseType.parse("Float64");
        Object nan = RowBinaryDecoder.decode(f64, new RowBinaryInput(hex("000000000000f87f")), OPTIONS);
        assertEquals(true, JdbcValues.asBoolean(new JdbcValues.Column(1, "v", f64), nan));
        Object half = RowBinaryDecoder.decode(f64, new RowBinaryInput(hex("000000000000e03f")), OPTIONS);
        assertEquals(true, JdbcValues.asBoolean(new JdbcValues.Column(1, "v", f64), half));

        // getBytes on an address. Refusing was over-strict: an address is bytes, and the
        // reference has an explicit branch for it (getPrimitiveArray: value instanceof
        // InetAddress -> getAddress()), so this is a considered answer and not a side effect.
        ClickHouseType ip4 = ClickHouseType.parse("IPv4");
        Object v4 = RowBinaryDecoder.decode(ip4, new RowBinaryInput(hex("0101a8c0")), OPTIONS);
        assertArrayEquals(
                new byte[] {(byte) 192, (byte) 168, 1, 1},
                JdbcValues.asBytes(new JdbcValues.Column(1, "v", ip4), v4));

        ClickHouseType ip6 = ClickHouseType.parse("IPv6");
        Object v6 = RowBinaryDecoder.decode(
                ip6, new RowBinaryInput(hex("20010db8000000000000000000000001")), OPTIONS);
        assertEquals(16, JdbcValues.asBytes(new JdbcValues.Column(1, "v", ip6), v6).length);
    }
}
