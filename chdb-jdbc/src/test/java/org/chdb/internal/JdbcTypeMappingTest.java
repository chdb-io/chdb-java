package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metadata clickhouse-jdbc reports for every type in the parity matrix, and the assertion
 * that we report the same.
 *
 * <p>Captured by reading the same 84 expressions through clickhouse-jdbc 0.10.0 against
 * clickhouse-server 26.7.3. Matching it is the point: an application moving between the two
 * drivers should not see a column's type change underneath it.
 *
 * <p>Where we deliberately disagree, the disagreement is listed in {@link #DIVERGENCES} with
 * its reason. Anything else that differs fails — which is what stops a divergence being
 * introduced by accident and then discovered by a user.
 */
class JdbcTypeMappingTest {

    private static final class Ref {
        final String typeName;
        final int jdbcType;
        final String className;
        final int precision;
        final int scale;
        final int displaySize;
        final boolean signed;
        final boolean nullable;

        Ref(String typeName, int jdbcType, String className, int precision, int scale,
                int displaySize, boolean signed, boolean nullable) {
            this.typeName = typeName;
            this.jdbcType = jdbcType;
            this.className = className;
            this.precision = precision;
            this.scale = scale;
            this.displaySize = displaySize;
            this.signed = signed;
            this.nullable = nullable;
        }
    }

    private static Ref row(String t, int jt, String cn, int p, int s, int d, boolean sg, boolean nl) {
        return new Ref(t, jt, cn, p, s, d, sg, nl);
    }

    private static final List<Ref> REFERENCE = Arrays.asList(
        row("Array(Array(UInt8))", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("Array(Int32)", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("Array(Map(String, UInt8))", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("Array(Nullable(UInt8))", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("Array(String)", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("Array(UInt8)", 2003, "java.sql.Array", 0, 0, 80, false, false),
        row("BFloat16", 6, "java.lang.Float", 3, 0, 80, true, false),
        row("Bool", 16, "java.lang.Boolean", 1, 0, 80, true, false),
        row("Date", 91, "java.sql.Date", 10, 0, 80, false, false),
        row("Date32", 91, "java.sql.Date", 10, 0, 80, false, false),
        row("DateTime", 93, "java.sql.Timestamp", 29, 0, 80, false, false),
        row("DateTime('Asia/Shanghai')", 93, "java.sql.Timestamp", 29, 0, 80, false, false),
        row("DateTime64(3)", 93, "java.sql.Timestamp", 29, 3, 80, false, false),
        row("DateTime64(6)", 93, "java.sql.Timestamp", 29, 6, 80, false, false),
        row("DateTime64(9, 'UTC')", 93, "java.sql.Timestamp", 29, 9, 80, false, false),
        row("DateTime64(9)", 93, "java.sql.Timestamp", 29, 9, 80, false, false),
        row("Decimal(18, 0)", 3, "java.math.BigDecimal", 18, 0, 80, true, false),
        row("Decimal(18, 2)", 3, "java.math.BigDecimal", 18, 2, 80, true, false),
        row("Decimal(18, 8)", 3, "java.math.BigDecimal", 18, 8, 80, true, false),
        row("Decimal(38, 20)", 3, "java.math.BigDecimal", 38, 20, 80, true, false),
        row("Decimal(76, 40)", 3, "java.math.BigDecimal", 76, 40, 80, true, false),
        row("Decimal(9, 4)", 3, "java.math.BigDecimal", 9, 4, 80, true, false),
        row("Dynamic", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("Enum16('zero' = 0, 'big' = 30000)", 12, "java.lang.String", 0, 0, 80, false, false),
        row("Enum8('' = 0, 'a' = 1)", 12, "java.lang.String", 0, 0, 80, false, false),
        row("Enum8('a' = 6, 'b' = 7)", 12, "java.lang.String", 0, 0, 80, false, false),
        row("Enum8('neg' = -5, '' = 0)", 12, "java.lang.String", 0, 0, 80, false, false),
        row("FixedString(5)", 12, "java.lang.String", 5, 0, 80, false, false),
        row("FixedString(6)", 12, "java.lang.String", 6, 0, 80, false, false),
        row("Float32", 6, "java.lang.Float", 12, 0, 80, true, false),
        row("Float64", 8, "java.lang.Double", 22, 0, 80, true, false),
        row("Int128", 2, "java.math.BigInteger", 39, 0, 80, true, false),
        row("Int16", 5, "java.lang.Short", 5, 0, 80, true, false),
        row("Int256", 2, "java.math.BigInteger", 77, 0, 80, true, false),
        row("Int32", 4, "java.lang.Integer", 10, 0, 80, true, false),
        row("Int64", -5, "java.lang.Long", 19, 0, 80, true, false),
        row("Int8", -6, "java.lang.Byte", 3, 0, 80, true, false),
        row("IntervalDay", -5, "java.lang.Long", 19, 0, 80, true, false),
        row("IPv4", 1111, "java.lang.Object", 10, 0, 80, false, false),
        row("IPv6", 1111, "java.lang.Object", 39, 0, 80, false, false),
        row("JSON", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("LineString", 2003, "java.sql.Array", 0, 0, 80, true, false),
        row("LowCardinality(Nullable(String))", 12, "java.lang.String", 0, 0, 80, false, true),
        row("LowCardinality(String)", 12, "java.lang.String", 0, 0, 80, false, false),
        row("Map(String, Int32)", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("Map(String, UInt8)", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("Nullable(Int32)", 4, "java.lang.Integer", 10, 0, 80, true, true),
        row("Nullable(String)", 12, "java.lang.String", 0, 0, 80, false, true),
        row("Point", 2003, "java.sql.Array", 0, 0, 80, true, false),
        row("Polygon", 2003, "java.sql.Array", 0, 0, 80, true, false),
        row("Ring", 2003, "java.sql.Array", 0, 0, 80, true, false),
        row("SimpleAggregateFunction(sum, Int64)", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("String", 12, "java.lang.String", 0, 0, 80, false, false),
        row("Time", 92, "java.sql.Time", 9, 0, 80, false, false),
        row("Time64(3)", 92, "java.sql.Time", 9, 3, 80, false, false),
        row("Tuple(n Int32, s String)", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("Tuple(UInt8, String)", 1111, "java.lang.Object", 0, 0, 80, false, false),
        row("UInt128", 2, "java.math.BigInteger", 39, 0, 80, false, false),
        row("UInt16", 4, "java.lang.Integer", 5, 0, 80, false, false),
        row("UInt256", 2, "java.math.BigInteger", 78, 0, 80, false, false),
        row("UInt32", -5, "java.lang.Long", 10, 0, 80, false, false),
        row("UInt64", 2, "java.math.BigInteger", 20, 0, 80, false, false),
        row("UInt8", 5, "java.lang.Short", 3, 0, 80, false, false),
        row("UUID", 1111, "java.util.UUID", 69, 0, 80, false, false),
        row("Variant(Int64, String)", 1111, "java.lang.Object", 0, 0, 80, false, false));

    /**
     * "typeName|field" for each place we answer differently on purpose.
     *
     * <p>Two reasons only, and both are the reference being wrong by the specification rather
     * than merely different:
     *
     * <ul>
     *   <li>{@code Float32} and {@code BFloat16} are {@code REAL}. {@code REAL} is JDBC's
     *       single-precision code; {@code FLOAT} is double precision.
     *   <li>{@code getColumnClassName} is defined as the class {@code getObject} manufactures.
     *       The reference answers {@code java.lang.Object} for {@code IPv4}, {@code IPv6},
     *       {@code Map}, {@code Tuple} and {@code JSON}, and {@code java.sql.Array} for the
     *       geometry types, while its own {@code getObject} returns an {@code InetAddress}, a
     *       {@code Map}, an {@code Object[]} and {@code double[]} respectively.
     * </ul>
     */
    private static final Set<String> DIVERGENCES = new LinkedHashSet<>(Arrays.asList(
            "Float32|jdbcType",
            "BFloat16|jdbcType",
            "IPv4|className",
            "IPv6|className",
            "JSON|className",
            "Map(String, Int32)|className",
            "Map(String, UInt8)|className",
            "Tuple(UInt8, String)|className",
            "Tuple(n Int32, s String)|className",
            "Point|className",
            "Ring|className",
            "LineString|className",
            "Polygon|className"));

    @Test
    @DisplayName("our metadata matches clickhouse-jdbc everywhere we have not chosen to differ")
    void matchesTheReference() {
        List<String> unexpected = new ArrayList<>();
        Set<String> exercised = new LinkedHashSet<>();

        for (Ref r : REFERENCE) {
            ClickHouseType t = ClickHouseType.parse(r.typeName);
            check(unexpected, exercised, r.typeName, "jdbcType", r.jdbcType, JdbcTypeMapping.jdbcType(t));
            check(unexpected, exercised, r.typeName, "className", r.className, JdbcTypeMapping.className(t));
            check(unexpected, exercised, r.typeName, "precision", r.precision, JdbcTypeMapping.precision(t));
            check(unexpected, exercised, r.typeName, "scale", r.scale, JdbcTypeMapping.scale(t));
            check(unexpected, exercised, r.typeName, "displaySize", r.displaySize, JdbcTypeMapping.displaySize(t));
            check(unexpected, exercised, r.typeName, "signed", r.signed, JdbcTypeMapping.isSigned(t));
            check(unexpected, exercised, r.typeName, "nullable", r.nullable, t.isNullable());
        }

        assertTrue(unexpected.isEmpty(), "undeclared differences from clickhouse-jdbc:\n  "
                + String.join("\n  ", unexpected));

        // A divergence that no longer happens is a stale claim in the list above, and leaving
        // it there would excuse a future accidental one.
        Set<String> stale = new LinkedHashSet<>(DIVERGENCES);
        stale.removeAll(exercised);
        assertTrue(stale.isEmpty(), "declared divergences that no longer occur: " + stale);
    }

    private static void check(List<String> unexpected, Set<String> exercised,
            String typeName, String field, Object reference, Object ours) {
        String key = typeName + "|" + field;
        boolean same = reference.equals(ours);
        if (DIVERGENCES.contains(key)) {
            if (same) {
                // Declared as different but is not: caught by the staleness check.
                return;
            }
            exercised.add(key);
            return;
        }
        if (!same) {
            unexpected.add(key + ": clickhouse-jdbc=" + reference + " ours=" + ours);
        }
    }

    @Test
    @DisplayName("the reference table still covers the types worth covering")
    void tableIsNotSilentlyShrinking() {
        assertEquals(65, REFERENCE.size());
    }
}
