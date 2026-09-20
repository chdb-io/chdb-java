package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chdb.internal.ClickHouseType.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The type names in here are the ones the engine actually writes, taken from a
 * {@code RowBinaryWithNamesAndTypes} header rather than invented, plus the awkward shapes the
 * grammar permits and a regular expression would get wrong.
 */
class ClickHouseTypeTest {

    @Test
    @DisplayName("the plain types classify and keep their name")
    void scalars() {
        assertEquals(Kind.INT8, ClickHouseType.parse("Int8").kind());
        assertEquals(Kind.UINT64, ClickHouseType.parse("UInt64").kind());
        assertEquals(Kind.INT128, ClickHouseType.parse("Int128").kind());
        assertEquals(Kind.INT256, ClickHouseType.parse("Int256").kind());
        assertEquals(Kind.FLOAT32, ClickHouseType.parse("Float32").kind());
        assertEquals(Kind.BFLOAT16, ClickHouseType.parse("BFloat16").kind());
        assertEquals(Kind.BOOL, ClickHouseType.parse("Bool").kind());
        assertEquals(Kind.IPV4, ClickHouseType.parse("IPv4").kind());
        assertEquals(Kind.IPV6, ClickHouseType.parse("IPv6").kind());
        assertEquals(Kind.UUID, ClickHouseType.parse("UUID").kind());
        assertEquals(Kind.DATE, ClickHouseType.parse("Date").kind());
        assertEquals(Kind.DATE32, ClickHouseType.parse("Date32").kind());
        assertEquals(Kind.JSON, ClickHouseType.parse("JSON").kind());
        assertEquals(Kind.DYNAMIC, ClickHouseType.parse("Dynamic").kind());
        assertEquals("Int8", ClickHouseType.parse("Int8").name());
    }

    @Test
    @DisplayName("FixedString keeps its width, which decoding needs")
    void fixedString() {
        ClickHouseType t = ClickHouseType.parse("FixedString(6)");
        assertEquals(Kind.FIXED_STRING, t.kind());
        assertEquals(6, t.fixedLength());
        assertEquals("FixedString(6)", t.name());
    }

    @Test
    @DisplayName("Decimal carries precision and scale, whichever spelling the engine used")
    void decimals() {
        ClickHouseType d = ClickHouseType.parse("Decimal(38, 20)");
        assertEquals(Kind.DECIMAL, d.kind());
        assertEquals(38, d.precision());
        assertEquals(20, d.scale());

        // Decimal32/64/128/256 put the width in the name and only the scale in brackets, so the
        // precision has to come from the name or ResultSetMetaData reports zero for it.
        ClickHouseType d32 = ClickHouseType.parse("Decimal32(4)");
        assertEquals(9, d32.precision());
        assertEquals(4, d32.scale());
        assertEquals(18, ClickHouseType.parse("Decimal64(8)").precision());
        assertEquals(38, ClickHouseType.parse("Decimal128(20)").precision());
        assertEquals(76, ClickHouseType.parse("Decimal256(40)").precision());
    }

    @Test
    @DisplayName("DateTime and DateTime64 separate scale from timezone")
    void temporal() {
        ClickHouseType plain = ClickHouseType.parse("DateTime");
        assertEquals(Kind.DATETIME, plain.kind());
        assertNull(plain.timeZone());

        ClickHouseType zoned = ClickHouseType.parse("DateTime('Asia/Shanghai')");
        assertEquals(Kind.DATETIME, zoned.kind());
        assertEquals("Asia/Shanghai", zoned.timeZone());

        ClickHouseType dt64 = ClickHouseType.parse("DateTime64(3)");
        assertEquals(Kind.DATETIME64, dt64.kind());
        assertEquals(3, dt64.scale());
        assertNull(dt64.timeZone());

        ClickHouseType dt64z = ClickHouseType.parse("DateTime64(9, 'UTC')");
        assertEquals(9, dt64z.scale());
        assertEquals("UTC", dt64z.timeZone());

        assertEquals(3, ClickHouseType.parse("Time64(3)").scale());
        assertEquals(Kind.TIME, ClickHouseType.parse("Time").kind());
    }

    @Test
    @DisplayName("enum members are recoverable in both directions")
    void enums() {
        ClickHouseType e = ClickHouseType.parse("Enum8('a' = 6, 'b' = 7)");
        assertEquals(Kind.ENUM8, e.kind());
        assertEquals(6L, e.enumValueByName().get("a"));
        assertEquals("b", e.enumNameByValue().get(7L));

        // The wire carries the number, so byValue is the direction that matters, and zero and
        // negative members are the ones clickhouse-jdbc has a dedicated test for.
        ClickHouseType z = ClickHouseType.parse("Enum8('' = 0, 'neg' = -5)");
        assertEquals("", z.enumNameByValue().get(0L));
        assertEquals("neg", z.enumNameByValue().get(-5L));

        ClickHouseType big = ClickHouseType.parse("Enum16('zero' = 0, 'big' = 30000, 'nb' = -20000)");
        assertEquals(Kind.ENUM16, big.kind());
        assertEquals("big", big.enumNameByValue().get(30000L));
        assertEquals("nb", big.enumNameByValue().get(-20000L));
    }

    @Test
    @DisplayName("an enum label may contain a comma, a bracket or a quote")
    void enumLabelsAreNotTokens() {
        // Why the parser is hand-written: none of these survive splitting on ',' or matching a
        // pattern, and all of them are legal ClickHouse.
        ClickHouseType comma = ClickHouseType.parse("Enum8('a,b' = 1, 'c' = 2)");
        assertEquals(2, comma.enumNameByValue().size());
        assertEquals("a,b", comma.enumNameByValue().get(1L));

        ClickHouseType bracket = ClickHouseType.parse("Enum8(')' = 1, '(' = 2)");
        assertEquals(")", bracket.enumNameByValue().get(1L));
        assertEquals("(", bracket.enumNameByValue().get(2L));

        ClickHouseType quoted = ClickHouseType.parse("Enum8('it\\'s' = 1)");
        assertEquals("it's", quoted.enumNameByValue().get(1L));

        ClickHouseType equals = ClickHouseType.parse("Enum8('a=b' = 3)");
        assertEquals("a=b", equals.enumNameByValue().get(3L));
    }

    @Test
    @DisplayName("Nullable and LowCardinality are wrappers, and unwrap to the real type")
    void wrappers() {
        ClickHouseType n = ClickHouseType.parse("Nullable(Int32)");
        assertEquals(Kind.NULLABLE, n.kind());
        assertTrue(n.isNullable());
        assertEquals(Kind.INT32, n.unwrapped().kind());

        ClickHouseType lc = ClickHouseType.parse("LowCardinality(String)");
        assertEquals(Kind.LOW_CARDINALITY, lc.kind());
        assertFalse(lc.isNullable());
        assertEquals(Kind.STRING, lc.unwrapped().kind());

        ClickHouseType both = ClickHouseType.parse("LowCardinality(Nullable(String))");
        assertTrue(both.isNullable());
        assertEquals(Kind.STRING, both.unwrapped().kind());

        assertFalse(ClickHouseType.parse("Int32").isNullable());
    }

    @Test
    @DisplayName("composites nest, and a tuple's field names are separated from its types")
    void composites() {
        ClickHouseType a = ClickHouseType.parse("Array(Nullable(Int32))");
        assertEquals(Kind.ARRAY, a.kind());
        assertEquals(Kind.NULLABLE, a.arguments().get(0).kind());

        ClickHouseType nested = ClickHouseType.parse("Array(Array(UInt8))");
        assertEquals(Kind.ARRAY, nested.arguments().get(0).kind());
        assertEquals(Kind.UINT8, nested.arguments().get(0).arguments().get(0).kind());

        ClickHouseType m = ClickHouseType.parse("Map(String, UInt8)");
        assertEquals(Kind.MAP, m.kind());
        assertEquals(2, m.arguments().size());
        assertEquals(Kind.STRING, m.arguments().get(0).kind());
        assertEquals(Kind.UINT8, m.arguments().get(1).kind());

        ClickHouseType positional = ClickHouseType.parse("Tuple(UInt8, String)");
        assertEquals(Kind.TUPLE, positional.kind());
        assertEquals(2, positional.arguments().size());
        assertTrue(positional.fieldNames().isEmpty());

        ClickHouseType named = ClickHouseType.parse("Tuple(n Int32, s String)");
        assertEquals(java.util.Arrays.asList("n", "s"), named.fieldNames());
        assertEquals(Kind.INT32, named.arguments().get(0).kind());

        // The space inside Map(String, Int32) is not a field-name separator.
        ClickHouseType tupleOfMap = ClickHouseType.parse("Tuple(Map(String, Int32))");
        assertTrue(tupleOfMap.fieldNames().isEmpty());
        assertEquals(Kind.MAP, tupleOfMap.arguments().get(0).kind());

        ClickHouseType namedMap = ClickHouseType.parse("Tuple(m Map(String, Int32))");
        assertEquals(java.util.Arrays.asList("m"), namedMap.fieldNames());
        assertEquals(Kind.MAP, namedMap.arguments().get(0).kind());
    }

    @Test
    @DisplayName("aggregate function types expose their argument types, not the function")
    void aggregateFunctions() {
        ClickHouseType s = ClickHouseType.parse("SimpleAggregateFunction(sum, Int64)");
        assertEquals(Kind.SIMPLE_AGGREGATE_FUNCTION, s.kind());
        assertEquals(1, s.arguments().size());
        assertEquals(Kind.INT64, s.arguments().get(0).kind());

        ClickHouseType a = ClickHouseType.parse("AggregateFunction(quantiles(0.5, 0.9), UInt64)");
        assertEquals(Kind.AGGREGATE_FUNCTION, a.kind());
        assertEquals(Kind.UINT64, a.arguments().get(0).kind());
    }

    @Test
    @DisplayName("an unrecognised type keeps its name instead of failing the query")
    void unknownDegradesGracefully() {
        // A type from a future ClickHouse must cost the caller that one column, not the
        // statement: metadata can still report what the engine said.
        ClickHouseType t = ClickHouseType.parse("SomeTypeFrom2030(Int32, 'x')");
        assertEquals(Kind.UNKNOWN, t.kind());
        assertEquals("SomeTypeFrom2030(Int32, 'x')", t.name());

        assertEquals(Kind.INTERVAL, ClickHouseType.parse("IntervalDay").kind());
        assertEquals(Kind.INTERVAL, ClickHouseType.parse("IntervalNanosecond").kind());
    }

    @Test
    @DisplayName("a malformed name is rejected rather than half-parsed")
    void malformed() {
        assertThrows(IllegalArgumentException.class, () -> ClickHouseType.parse("Array(Int32"));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseType.parse("Enum8('a' = )"));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseType.parse("FixedString(x)"));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseType.parse("Int32 trailing"));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseType.parse(null));
    }
}
