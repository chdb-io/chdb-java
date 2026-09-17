import java.util.*;

/**
 * The type/value matrix. Values are the boundary and awkward cases clickhouse-jdbc's own
 * JdbcDataTypeTests encodes, plus the ones its test names say they have been bitten by:
 * enum zero and negative members, decimal truncation, unsigned overflow into signed Java,
 * binary strings that are not UTF-8, and the newer types.
 *
 * Expressions rather than tables on purpose: both drivers then see an identical column type
 * with no INSERT path in between, so a difference is in reading.
 */
final class Cases {
    private static void add(List<String[]> l, String label, String expr) { l.add(new String[]{label, expr}); }

    static List<String[]> all() {
        List<String[]> c = new ArrayList<>();

        // ---- signed integers at their boundaries
        add(c, "Int8 min", "toInt8(-128)");
        add(c, "Int8 max", "toInt8(127)");
        add(c, "Int16 min", "toInt16(-32768)");
        add(c, "Int32 min", "toInt32(-2147483648)");
        add(c, "Int64 min", "toInt64(-9223372036854775808)");
        add(c, "Int64 max", "toInt64(9223372036854775807)");
        add(c, "Int128 max", "toInt128('170141183460469231731687303715884105727')");
        add(c, "Int256 max", "toInt256('57896044618658097711785492504343953926634992332820282019728792003956564819967')");

        // ---- unsigned, where the top of the range does not fit the signed Java type
        add(c, "UInt8 max", "toUInt8(255)");
        add(c, "UInt16 max", "toUInt16(65535)");
        add(c, "UInt32 max", "toUInt32(4294967295)");
        add(c, "UInt64 max", "toUInt64(18446744073709551615)");
        add(c, "UInt64 mid", "toUInt64(9223372036854775808)");
        add(c, "UInt128 max", "toUInt128('340282366920938463463374607431768211455')");
        add(c, "UInt256 max", "toUInt256('115792089237316195423570985008687907853269984665640564039457584007913129639935')");

        // ---- floats
        add(c, "Float32", "toFloat32(3.4028235e38)");
        add(c, "Float32 tiny", "toFloat32(1.1754944e-38)");
        add(c, "Float64", "toFloat64(1.7976931348623157e308)");
        add(c, "Float64 inf", "toFloat64('inf')");
        add(c, "Float64 -inf", "toFloat64('-inf')");
        add(c, "Float64 nan", "toFloat64('nan')");
        add(c, "Float64 -0.0", "toFloat64('-0.0')");
        add(c, "BFloat16", "toBFloat16(1.5)");

        // ---- decimals, including the scale/rounding cases their suite singles out
        add(c, "Decimal32(4)", "toDecimal32(1234.5678, 4)");
        add(c, "Decimal64(8)", "toDecimal64(12345678.12345678, 8)");
        add(c, "Decimal128(20)", "toDecimal128('1.00000000000000000001', 20)");
        add(c, "Decimal256(40)", "toDecimal256('1.0000000000000000000000000000000000000001', 40)");
        add(c, "Decimal negative", "toDecimal64(-0.5, 2)");
        add(c, "Decimal scale 0", "toDecimal64(42, 0)");

        // ---- strings, including bytes that are not text
        add(c, "String", "'hello'");
        add(c, "String unicode", "'héllo 数据库 🎉'");
        add(c, "String empty", "''");
        add(c, "String with NUL", "'a\\0b'");
        add(c, "String invalid utf8", "unhex('a3a312a0df134e8c8774d453dbfc3495')");
        add(c, "FixedString(6)", "toFixedString('fixed', 6)");
        add(c, "FixedString w/ NUL pad", "toFixedString('ab', 5)");

        // ---- enums: zero and negative members are what their testEnumZeroLikeValues is about
        add(c, "Enum8 positive", "CAST('b', 'Enum8(\\'a\\' = 6, \\'b\\' = 7)')");
        add(c, "Enum8 zero", "CAST('', 'Enum8(\\'\\' = 0, \\'a\\' = 1)')");
        add(c, "Enum8 negative", "CAST('neg', 'Enum8(\\'\\' = 0, \\'neg\\' = -5)')");
        add(c, "Enum16 big", "CAST('big', 'Enum16(\\'zero\\' = 0, \\'big\\' = 30000)')");

        // ---- identifiers and addresses
        add(c, "UUID", "toUUID('61f0c404-5cb3-11e7-907b-a6006ad3dba0')");
        add(c, "UUID zero", "toUUID('00000000-0000-0000-0000-000000000000')");
        add(c, "IPv4", "toIPv4('192.168.1.1')");
        add(c, "IPv6", "toIPv6('2001:db8::1')");
        add(c, "IPv6 v4-mapped", "toIPv6('::ffff:192.168.1.1')");
        add(c, "Bool true", "true");
        add(c, "Bool false", "false");

        // ---- temporal
        add(c, "Date", "toDate('2026-09-17')");
        add(c, "Date epoch", "toDate('1970-01-01')");
        add(c, "Date32 pre-epoch", "toDate32('1925-01-01')");
        add(c, "DateTime", "toDateTime('2026-09-17 12:34:56')");
        add(c, "DateTime tz", "toDateTime('2026-09-17 12:34:56', 'Asia/Shanghai')");
        add(c, "DateTime64(3)", "toDateTime64('2026-09-17 12:34:56.789', 3)");
        add(c, "DateTime64(6)", "toDateTime64('2026-09-17 12:34:56.789012', 6)");
        add(c, "DateTime64(9)", "toDateTime64('2026-09-17 12:34:56.789012345', 9)");
        add(c, "DateTime64(9) tz", "toDateTime64('2026-09-17 12:34:56.789012345', 9, 'UTC')");
        add(c, "DateTime64 pre-epoch", "toDateTime64('1960-01-01 00:00:00.000', 3)");
        add(c, "Time", "CAST('12:34:56' AS Time)");
        add(c, "Time64(3)", "CAST('12:34:56.789' AS Time64(3))");
        add(c, "IntervalDay", "INTERVAL 3 DAY");

        // ---- nullability and low cardinality
        add(c, "Nullable(Int32) null", "CAST(NULL AS Nullable(Int32))");
        add(c, "Nullable(String) null", "CAST(NULL AS Nullable(String))");
        add(c, "Nullable(Int32) value", "CAST(7 AS Nullable(Int32))");
        add(c, "LowCardinality(String)", "CAST('x' AS LowCardinality(String))");
        add(c, "LowCardinality(Nullable)", "CAST(NULL AS LowCardinality(Nullable(String)))");

        // ---- composites
        add(c, "Array(Int32)", "[1, 2, 3]");
        add(c, "Array(String)", "['a', 'b']");
        add(c, "Array empty", "CAST([] AS Array(Int32))");
        add(c, "Array(Nullable)", "[1, NULL, 3]");
        add(c, "Array(Array(Int32))", "[[1, 2], [3]]");
        add(c, "Tuple", "(1, 'a')");
        add(c, "Tuple named", "CAST((1, 'a') AS Tuple(n Int32, s String))");
        add(c, "Map(String,Int32)", "map('a', 1, 'b', 2)");
        add(c, "Map empty", "CAST(map() AS Map(String, Int32))");
        add(c, "Array(Map)", "[map('a', 1)]");

        // ---- the newer, semi-structured types
        add(c, "JSON", "CAST('{\"a\":1,\"b\":\"x\"}' AS JSON)");
        add(c, "Dynamic int", "CAST(42 AS Dynamic)");
        add(c, "Dynamic string", "CAST('s' AS Dynamic)");
        add(c, "Variant", "CAST(42 AS Variant(Int64, String))");

        // ---- geo
        add(c, "Point", "CAST((1.0, 2.0) AS Point)");
        add(c, "Ring", "CAST([(0.0,0.0),(1.0,0.0),(1.0,1.0)] AS Ring)");
        add(c, "LineString", "CAST([(0.0,0.0),(1.0,1.0)] AS LineString)");
        add(c, "Polygon", "CAST([[(0.0,0.0),(1.0,0.0),(1.0,1.0)]] AS Polygon)");

        // ---- aggregate state
        add(c, "SimpleAggregateFunction", "CAST(1 AS SimpleAggregateFunction(sum, Int64))");

        return c;
    }
}
