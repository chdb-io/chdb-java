package org.chdb.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One column's type, parsed from its Arrow C Data Interface format string.
 *
 * <p>The format string is the driver's source of truth for types. Going through Arrow rather
 * than reading ClickHouse type names means the mapping is decided by the engine's own
 * converter: {@code LowCardinality(String)} arrives as {@code "u"}, {@code Nullable(Int32)}
 * as {@code "i"} with the nullable flag, {@code Enum8} as its underlying integer. The driver
 * does not have to know that, and cannot drift from it.
 *
 * <p>The V1 type matrix is work plan section 5.9. A format this class does not recognize
 * becomes {@link Kind#UNSUPPORTED}, which the result set reports as a typed error naming the
 * column -- never as a silently wrong value.
 */
public final class ArrowFieldType {

    public enum Kind {
        BOOL,
        INT8,
        UINT8,
        INT16,
        UINT16,
        INT32,
        UINT32,
        INT64,
        UINT64,
        FLOAT16,
        FLOAT32,
        FLOAT64,
        DECIMAL,
        UTF8,
        LARGE_UTF8,
        BINARY,
        LARGE_BINARY,
        FIXED_SIZE_BINARY,
        DATE32,
        DATE64,
        TIMESTAMP,
        TIME32,
        TIME64,
        DURATION,
        UNSUPPORTED,
    }

    /** Resolution of a temporal type, and the factor that converts it to its Java unit. */
    public enum TimeUnit {
        SECOND,
        MILLI,
        MICRO,
        NANO,
    }

    private final String format;
    private final Kind kind;
    private final int precision;
    private final int scale;
    private final int byteWidth;
    private final TimeUnit unit;
    private final String timezone;
    private final boolean nullable;
    private final boolean dictionaryEncoded;

    private ArrowFieldType(
            String format,
            Kind kind,
            int precision,
            int scale,
            int byteWidth,
            TimeUnit unit,
            String timezone,
            boolean nullable,
            boolean dictionaryEncoded) {
        this.format = format;
        this.kind = kind;
        this.precision = precision;
        this.scale = scale;
        this.byteWidth = byteWidth;
        this.unit = unit;
        this.timezone = timezone;
        this.nullable = nullable;
        this.dictionaryEncoded = dictionaryEncoded;
    }

    public String format() {
        return format;
    }

    public Kind kind() {
        return kind;
    }

    /** Decimal precision, or 0. */
    public int precision() {
        return precision;
    }

    /** Decimal scale, or 0. */
    public int scale() {
        return scale;
    }

    /** Element width in bytes for fixed-width kinds, or 0 for variable-length ones. */
    public int byteWidth() {
        return byteWidth;
    }

    /** Temporal resolution, or null for non-temporal kinds. */
    public TimeUnit unit() {
        return unit;
    }

    /** IANA zone the engine tagged a timestamp with, or null if it carried none. */
    public String timezone() {
        return timezone;
    }

    public boolean nullable() {
        return nullable;
    }

    public boolean supported() {
        return kind != Kind.UNSUPPORTED;
    }

    /**
     * Parses one Arrow format string.
     *
     * @param dictionaryEncoded whether the schema field carried a dictionary; such a column
     *     reuses its value type's format string, so the format alone cannot reveal it
     */
    public static ArrowFieldType parse(String format, boolean nullable, boolean dictionaryEncoded) {
        String value = format == null ? "" : format;
        if (dictionaryEncoded) {
            // V1 asks the engine for materialized LowCardinality columns, so a dictionary
            // here means the caller turned that off. Its indices/values pair is a different
            // buffer layout than anything below, so refuse it rather than mis-slice it.
            return unsupported(value, nullable, true);
        }

        if (value.length() == 1) {
            switch (value.charAt(0)) {
                case 'b':
                    return of(value, Kind.BOOL, 0, nullable);
                case 'c':
                    return of(value, Kind.INT8, 1, nullable);
                case 'C':
                    return of(value, Kind.UINT8, 1, nullable);
                case 's':
                    return of(value, Kind.INT16, 2, nullable);
                case 'S':
                    return of(value, Kind.UINT16, 2, nullable);
                case 'i':
                    return of(value, Kind.INT32, 4, nullable);
                case 'I':
                    return of(value, Kind.UINT32, 4, nullable);
                case 'l':
                    return of(value, Kind.INT64, 8, nullable);
                case 'L':
                    return of(value, Kind.UINT64, 8, nullable);
                case 'e':
                    return of(value, Kind.FLOAT16, 2, nullable);
                case 'f':
                    return of(value, Kind.FLOAT32, 4, nullable);
                case 'g':
                    return of(value, Kind.FLOAT64, 8, nullable);
                case 'u':
                    return of(value, Kind.UTF8, 0, nullable);
                case 'U':
                    return of(value, Kind.LARGE_UTF8, 0, nullable);
                case 'z':
                    return of(value, Kind.BINARY, 0, nullable);
                case 'Z':
                    return of(value, Kind.LARGE_BINARY, 0, nullable);
                default:
                    return unsupported(value, nullable, false);
            }
        }

        if (value.startsWith("d:")) {
            String[] parts = value.substring(2).split(",");
            if (parts.length < 2) {
                return unsupported(value, nullable, false);
            }
            int precision = parseIntOr(parts[0], -1);
            int scale = parseIntOr(parts[1], Integer.MIN_VALUE);
            int bits = parts.length >= 3 ? parseIntOr(parts[2], 128) : 128;
            if (precision < 0 || scale == Integer.MIN_VALUE || (bits != 128 && bits != 256)) {
                return unsupported(value, nullable, false);
            }
            return new ArrowFieldType(
                    value, Kind.DECIMAL, precision, scale, bits / 8, null, null, nullable, false);
        }

        if (value.startsWith("w:")) {
            int width = parseIntOr(value.substring(2), -1);
            if (width <= 0) {
                return unsupported(value, nullable, false);
            }
            return of(value, Kind.FIXED_SIZE_BINARY, width, nullable);
        }

        if (value.equals("tdD")) {
            return new ArrowFieldType(value, Kind.DATE32, 0, 0, 4, TimeUnit.SECOND, null, nullable, false);
        }
        if (value.equals("tdm")) {
            return new ArrowFieldType(value, Kind.DATE64, 0, 0, 8, TimeUnit.MILLI, null, nullable, false);
        }

        if (value.startsWith("ts") && value.length() >= 4 && value.charAt(3) == ':') {
            TimeUnit unit = unitOf(value.charAt(2));
            if (unit == null) {
                return unsupported(value, nullable, false);
            }
            // "tsu:" with nothing after the colon means a timestamp with no timezone.
            String zone = value.length() > 4 ? value.substring(4) : null;
            if (zone != null && zone.isEmpty()) {
                zone = null;
            }
            return new ArrowFieldType(value, Kind.TIMESTAMP, 0, 0, 8, unit, zone, nullable, false);
        }

        if (value.startsWith("tt") && value.length() == 3) {
            TimeUnit unit = unitOf(value.charAt(2));
            if (unit == null) {
                return unsupported(value, nullable, false);
            }
            boolean wide = unit == TimeUnit.MICRO || unit == TimeUnit.NANO;
            return new ArrowFieldType(
                    value, wide ? Kind.TIME64 : Kind.TIME32, 0, 0, wide ? 8 : 4, unit, null, nullable, false);
        }

        if (value.startsWith("tD") && value.length() == 3) {
            TimeUnit unit = unitOf(value.charAt(2));
            if (unit == null) {
                return unsupported(value, nullable, false);
            }
            return new ArrowFieldType(value, Kind.DURATION, 0, 0, 8, unit, null, nullable, false);
        }

        return unsupported(value, nullable, false);
    }

    private static TimeUnit unitOf(char code) {
        switch (code) {
            case 's':
                return TimeUnit.SECOND;
            case 'm':
                return TimeUnit.MILLI;
            case 'u':
                return TimeUnit.MICRO;
            case 'n':
                return TimeUnit.NANO;
            default:
                return null;
        }
    }

    private static ArrowFieldType of(String format, Kind kind, int byteWidth, boolean nullable) {
        return new ArrowFieldType(format, kind, 0, 0, byteWidth, null, null, nullable, false);
    }

    private static ArrowFieldType unsupported(String format, boolean nullable, boolean dictionaryEncoded) {
        return new ArrowFieldType(
                format, Kind.UNSUPPORTED, 0, 0, 0, null, null, nullable, dictionaryEncoded);
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ JDBC mapping

    /** {@link Types} constant for this column. */
    public int jdbcType() {
        switch (kind) {
            case BOOL:
                return Types.BOOLEAN;
            case INT8:
                return Types.TINYINT;
            case UINT8:
            case INT16:
                return Types.SMALLINT;
            case UINT16:
            case INT32:
                return Types.INTEGER;
            case UINT32:
            case INT64:
                return Types.BIGINT;
            // UInt64 has no JDBC integer type that holds it, so it is a NUMERIC carried as
            // BigInteger rather than a BIGINT that would be wrong above 2^63-1.
            case UINT64:
            case DECIMAL:
                return Types.NUMERIC;
            case FLOAT16:
            case FLOAT32:
                return Types.REAL;
            case FLOAT64:
                return Types.DOUBLE;
            case UTF8:
            case LARGE_UTF8:
                return Types.VARCHAR;
            case BINARY:
            case LARGE_BINARY:
                return Types.VARBINARY;
            case FIXED_SIZE_BINARY:
                return Types.BINARY;
            case DATE32:
            case DATE64:
                return Types.DATE;
            case TIMESTAMP:
                return timezone == null ? Types.TIMESTAMP : Types.TIMESTAMP_WITH_TIMEZONE;
            case TIME32:
            case TIME64:
                return Types.TIME;
            case DURATION:
                return Types.BIGINT;
            case UNSUPPORTED:
            default:
                return Types.OTHER;
        }
    }

    /** Class {@code getObject} returns for this column. */
    public Class<?> javaClass() {
        switch (kind) {
            case BOOL:
                return Boolean.class;
            case INT8:
                return Byte.class;
            case UINT8:
            case INT16:
                return Short.class;
            case UINT16:
            case INT32:
                return Integer.class;
            case UINT32:
            case INT64:
            case DURATION:
                return Long.class;
            case UINT64:
                return BigInteger.class;
            case DECIMAL:
                return BigDecimal.class;
            case FLOAT16:
            case FLOAT32:
                return Float.class;
            case FLOAT64:
                return Double.class;
            case UTF8:
            case LARGE_UTF8:
                return String.class;
            case BINARY:
            case LARGE_BINARY:
                return byte[].class;
            case FIXED_SIZE_BINARY:
                // A 16-byte fixed-size binary is how the engine exports UUID when asked for
                // a fixed byte array, and UUID is the useful Java shape for it.
                return byteWidth == 16 ? UUID.class : byte[].class;
            case DATE32:
            case DATE64:
                return LocalDate.class;
            case TIMESTAMP:
                return Instant.class;
            case TIME32:
            case TIME64:
                return LocalTime.class;
            case UNSUPPORTED:
            default:
                return Object.class;
        }
    }

    /** Name reported by {@code ResultSetMetaData.getColumnTypeName}. */
    public String typeName() {
        switch (kind) {
            case BOOL:
                return "Bool";
            case INT8:
                return "Int8";
            case UINT8:
                return "UInt8";
            case INT16:
                return "Int16";
            case UINT16:
                return "UInt16";
            case INT32:
                return "Int32";
            case UINT32:
                return "UInt32";
            case INT64:
                return "Int64";
            case UINT64:
                return "UInt64";
            case FLOAT16:
                return "Float16";
            case FLOAT32:
                return "Float32";
            case FLOAT64:
                return "Float64";
            case DECIMAL:
                return "Decimal(" + precision + ", " + scale + ")";
            case UTF8:
            case LARGE_UTF8:
                return "String";
            case BINARY:
            case LARGE_BINARY:
                return "Binary";
            case FIXED_SIZE_BINARY:
                return byteWidth == 16 ? "UUID" : "FixedString(" + byteWidth + ")";
            case DATE32:
                return "Date32";
            case DATE64:
                return "Date64";
            case TIMESTAMP:
                return timezone == null ? "DateTime64" : "DateTime64(" + timezone + ")";
            case TIME32:
            case TIME64:
                return "Time";
            case DURATION:
                return "Duration";
            case UNSUPPORTED:
            default:
                // The Arrow format string is the most useful thing to report: it is what an
                // "unsupported column type" error will quote, and it is greppable.
                return "Unsupported(arrow=" + format + (dictionaryEncoded ? ",dictionary" : "") + ")";
        }
    }

    /** Display width for {@code ResultSetMetaData.getColumnDisplaySize}. */
    public int displaySize() {
        switch (kind) {
            case BOOL:
                return 5;
            case INT8:
                return 4;
            case UINT8:
                return 3;
            case INT16:
                return 6;
            case UINT16:
                return 5;
            case INT32:
                return 11;
            case UINT32:
                return 10;
            case INT64:
            case DURATION:
                return 20;
            case UINT64:
                return 20;
            case DECIMAL:
                return precision + 2;
            case FLOAT16:
            case FLOAT32:
                return 15;
            case FLOAT64:
                return 24;
            case FIXED_SIZE_BINARY:
                return byteWidth == 16 ? 36 : byteWidth * 2;
            case DATE32:
            case DATE64:
                return 10;
            case TIMESTAMP:
                return 32;
            case TIME32:
            case TIME64:
                return 18;
            default:
                // Variable-length and unsupported: JDBC has no way to say "unbounded", and
                // Integer.MAX_VALUE is what other drivers report.
                return Integer.MAX_VALUE;
        }
    }

    /** {@code getPrecision}: digits for numerics, bytes for binary, 0 for the rest. */
    public int jdbcPrecision() {
        switch (kind) {
            case DECIMAL:
                return precision;
            case INT8:
                return 3;
            case UINT8:
                return 3;
            case INT16:
                return 5;
            case UINT16:
                return 5;
            case INT32:
                return 10;
            case UINT32:
                return 10;
            case INT64:
            case DURATION:
                return 19;
            case UINT64:
                return 20;
            case FLOAT16:
                return 5;
            case FLOAT32:
                return 8;
            case FLOAT64:
                return 17;
            case FIXED_SIZE_BINARY:
                return byteWidth;
            default:
                return 0;
        }
    }

    public boolean signed() {
        switch (kind) {
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case FLOAT16:
            case FLOAT32:
            case FLOAT64:
            case DECIMAL:
            case DURATION:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return typeName() + "[arrow=" + format + (nullable ? ",nullable" : "") + "]";
    }
}
