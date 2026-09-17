package org.chdb.internal;

import java.sql.Types;

/**
 * How a ClickHouse type appears through {@code ResultSetMetaData}.
 *
 * <h2>Where these answers come from</h2>
 * Captured from clickhouse-jdbc rather than invented: the same 84 expressions were read through
 * it and its metadata recorded, and this table reproduces it. Matching the reference driver is
 * the point — an application moving between them should not see a column's type change — so the
 * default is to agree, and every disagreement below is deliberate, listed, and there because
 * the reference answer is wrong by the JDBC specification rather than merely different.
 *
 * <h2>The deliberate disagreements</h2>
 * <ul>
 *   <li><b>{@code Float32} is {@code REAL}, not {@code FLOAT}.</b> {@code REAL} is the JDBC
 *       code for single precision and {@code FLOAT} is double; a 4-byte float is {@code REAL}.
 *   <li><b>{@code getColumnClassName} names the class we actually return.</b> The reference
 *       answers {@code java.lang.Object} for {@code IPv4}, {@code IPv6}, {@code Map} and the
 *       geometry types while returning an {@code InetAddress}, a {@code Map} and
 *       {@code double[]} from {@code getObject}. The specification defines this method as the
 *       class {@code getObject} manufactures, so answering {@code Object} is not a safe
 *       approximation, it is an inaccurate one.
 * </ul>
 *
 * <p>Everything else follows the reference, including the parts that look arbitrary: a
 * {@code DateTime} reports precision 29 whatever its scale, a display size of 80 is reported
 * for every type, and an {@code Enum} reports {@code VARCHAR} because its label is what
 * {@code getString} returns.
 */
public final class JdbcTypeMapping {

    private JdbcTypeMapping() {
    }

    /** {@code getColumnType}. */
    public static int jdbcType(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case INT8: return Types.TINYINT;
            case INT16: return Types.SMALLINT;
            case UINT8: return Types.SMALLINT;
            case INT32: return Types.INTEGER;
            case UINT16: return Types.INTEGER;
            case INT64: return Types.BIGINT;
            case UINT32: return Types.BIGINT;
            case INTERVAL: return Types.BIGINT;
            case INT128:
            case INT256:
            case UINT64:
            case UINT128:
            case UINT256: return Types.NUMERIC;
            case FLOAT32:
            case BFLOAT16: return Types.REAL;
            case FLOAT64: return Types.DOUBLE;
            case DECIMAL: return Types.DECIMAL;
            case BOOL: return Types.BOOLEAN;
            case STRING:
            case FIXED_STRING:
            case ENUM8:
            case ENUM16: return Types.VARCHAR;
            case DATE:
            case DATE32: return Types.DATE;
            case DATETIME:
            case DATETIME64: return Types.TIMESTAMP;
            case TIME:
            case TIME64: return Types.TIME;
            case ARRAY:
            case POINT:
            case RING:
            case LINESTRING:
            case MULTILINESTRING:
            case POLYGON:
            case MULTIPOLYGON: return Types.ARRAY;
            default: return Types.OTHER;
        }
    }

    /** {@code getColumnClassName}: the class {@code getObject} returns for this column. */
    public static String className(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case INT8: return Byte.class.getName();
            case INT16:
            case UINT8: return Short.class.getName();
            case INT32:
            case UINT16: return Integer.class.getName();
            case INT64:
            case UINT32:
            case INTERVAL: return Long.class.getName();
            case INT128:
            case INT256:
            case UINT64:
            case UINT128:
            case UINT256: return java.math.BigInteger.class.getName();
            case FLOAT32:
            case BFLOAT16: return Float.class.getName();
            case FLOAT64: return Double.class.getName();
            case DECIMAL: return java.math.BigDecimal.class.getName();
            case BOOL: return Boolean.class.getName();
            case STRING:
            case FIXED_STRING:
            case ENUM8:
            case ENUM16:
            case JSON: return String.class.getName();
            case DATE:
            case DATE32: return java.sql.Date.class.getName();
            case DATETIME:
            case DATETIME64: return java.sql.Timestamp.class.getName();
            case TIME:
            case TIME64: return java.sql.Time.class.getName();
            case UUID: return java.util.UUID.class.getName();
            case IPV4:
            case IPV6: return java.net.InetAddress.class.getName();
            case ARRAY: return java.sql.Array.class.getName();
            case POINT: return double[].class.getName();
            case RING:
            case LINESTRING: return double[][].class.getName();
            case MULTILINESTRING:
            case POLYGON: return double[][][].class.getName();
            case MULTIPOLYGON: return double[][][][].class.getName();
            case MAP: return java.util.Map.class.getName();
            case TUPLE:
            case NESTED: return Object[].class.getName();
            default: return Object.class.getName();
        }
    }

    /**
     * {@code getPrecision}.
     *
     * <p>Decimal digits for a number, characters for a string, and for the temporal types the
     * width of their textual form — which is where 29 for a timestamp and 10 for a date come
     * from. Zero where the concept does not apply, which is what the reference reports for
     * {@code String}, the composites and the semi-structured types.
     */
    public static int precision(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case INT8:
            case UINT8:
            case BFLOAT16: return 3;
            case INT16:
            case UINT16: return 5;
            case INT32:
            case UINT32:
            case DATE:
            case DATE32:
            case IPV4: return 10;
            case FLOAT32: return 12;
            case INT64: return 19;
            case INTERVAL: return 19;
            case UINT64: return 20;
            case FLOAT64: return 22;
            case DECIMAL: return t.precision();
            case INT128:
            case UINT128:
            case IPV6: return 39;
            case INT256: return 77;
            case UINT256: return 78;
            case BOOL: return 1;
            case FIXED_STRING: return t.fixedLength();
            case DATETIME:
            case DATETIME64: return 29;
            case TIME:
            case TIME64: return 9;
            case UUID: return 69;
            default: return 0;
        }
    }

    /** {@code getScale}: the sub-second or decimal scale, zero for everything else. */
    public static int scale(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case DECIMAL:
            case DATETIME64:
            case TIME64: return t.scale();
            default: return 0;
        }
    }

    /** {@code isSigned}. */
    public static boolean isSigned(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case INT128:
            case INT256:
            case FLOAT32:
            case FLOAT64:
            case BFLOAT16:
            case DECIMAL:
            case INTERVAL:
            case BOOL:
            // The reference reports the geometry types as signed, which is defensible: their
            // coordinates are.
            case POINT:
            case RING:
            case LINESTRING:
            case MULTILINESTRING:
            case POLYGON:
            case MULTIPOLYGON: return true;
            default: return false;
        }
    }

    /**
     * {@code getColumnDisplaySize}.
     *
     * <p>80 for everything, which is what the reference reports and is as meaningful as any
     * other guess: the width of a value is not a property of its type for most of these, and a
     * caller that needs a real width has the value.
     */
    public static int displaySize(ClickHouseType type) {
        return 80;
    }

    /** {@code isCaseSensitive}: true for the types whose text form carries meaning. */
    public static boolean isCaseSensitive(ClickHouseType type) {
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case STRING:
            case FIXED_STRING:
            case ENUM8:
            case ENUM16:
            case JSON: return true;
            default: return false;
        }
    }
}
