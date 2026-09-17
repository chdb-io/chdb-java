package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.List;
import org.chdb.internal.ClickHouseType.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every distinct type name engine v26.7.3 produced for the type-parity matrix, captured from
 * real {@code RowBinaryWithNamesAndTypes} headers by {@code scripts/run-type-parity.sh}.
 *
 * <p>Hand-written cases prove the grammar; these prove the grammar is the one the engine
 * actually speaks. Note what the engine normalises on the way out -- {@code Decimal32(4)} is
 * reported as {@code Decimal(9, 4)} -- which is the sort of thing only a captured corpus tells
 * you.
 *
 * <p>Regenerate after an engine bump: the parity harness prints the list.
 */
class ClickHouseTypeCorpusTest {

    private static final List<String> FROM_ENGINE =
            Arrays.asList(
                    "Int8",
                    "Int16",
                    "Int32",
                    "Int64",
                    "Int128",
                    "Int256",
                    "UInt8",
                    "UInt16",
                    "UInt32",
                    "UInt64",
                    "UInt128",
                    "UInt256",
                    "Float32",
                    "Float64",
                    "BFloat16",
                    "Decimal(9, 4)",
                    "Decimal(18, 8)",
                    "Decimal(38, 20)",
                    "Decimal(76, 40)",
                    "Decimal(18, 2)",
                    "Decimal(18, 0)",
                    "String",
                    "FixedString(6)",
                    "FixedString(5)",
                    "Enum8('a' = 6, 'b' = 7)",
                    "Enum8('' = 0, 'a' = 1)",
                    "Enum8('neg' = -5, '' = 0)",
                    "Enum16('zero' = 0, 'big' = 30000)",
                    "UUID",
                    "IPv4",
                    "IPv6",
                    "Bool",
                    "Date",
                    "Date32",
                    "DateTime",
                    "DateTime('Asia/Shanghai')",
                    "DateTime64(3)",
                    "DateTime64(6)",
                    "DateTime64(9)",
                    "DateTime64(9, 'UTC')",
                    "Time",
                    "Time64(3)",
                    "IntervalDay",
                    "Nullable(Int32)",
                    "Nullable(String)",
                    "LowCardinality(String)",
                    "LowCardinality(Nullable(String))",
                    "Array(UInt8)",
                    "Array(String)",
                    "Array(Int32)",
                    "Array(Nullable(UInt8))",
                    "Array(Array(UInt8))",
                    "Tuple(UInt8, String)",
                    "Tuple(n Int32, s String)",
                    "Map(String, UInt8)",
                    "Map(String, Int32)",
                    "Array(Map(String, UInt8))",
                    "JSON",
                    "Dynamic",
                    "Variant(Int64, String)",
                    "Point",
                    "Ring",
                    "LineString",
                    "Polygon",
                    "SimpleAggregateFunction(sum, Int64)");

    @Test
    @DisplayName("every type the engine emits parses, and none falls back to UNKNOWN")
    void everyEngineTypeParses() {
        for (String name : FROM_ENGINE) {
            ClickHouseType t = ClickHouseType.parse(name);
            assertNotEquals(Kind.UNKNOWN, t.kind(), "did not classify: " + name);
            // The name has to survive intact, because ResultSetMetaData reports it verbatim.
            assertEquals(name, t.name(), "name not preserved: " + name);
        }
    }

    @Test
    @DisplayName("the corpus still covers the types worth covering")
    void corpusIsNotSilentlyShrinking() {
        // A guard on the guard: if a regeneration drops most of the list, the test above keeps
        // passing while testing almost nothing.
        assertEquals(65, FROM_ENGINE.size());
    }
}
