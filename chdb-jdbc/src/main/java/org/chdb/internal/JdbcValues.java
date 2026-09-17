package org.chdb.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * Turns a decoded value into what a JDBC accessor returns.
 *
 * <h2>Why this is separate from decoding</h2>
 * The decoder produces the nearest thing that loses nothing: an {@code Enum} is its number, a
 * {@code String} is its bytes. Which of those a caller sees depends on which accessor they
 * called — {@code getString} on an enum wants the label and {@code getInt} wants the number —
 * so the choice belongs here, where the accessor is known.
 *
 * <h2>Where the answers come from</h2>
 * Captured from clickhouse-jdbc for the same 84 expressions and matched deliberately, down to
 * details that look like accidents until you see them next to each other: an array renders as
 * {@code [1, 2, 3]} but an array of strings as {@code ['a', 'b']} with the elements quoted, a
 * null element inside one renders as uppercase {@code NULL}, a {@code Point} renders as
 * {@code (1.0,2.0)} with no spaces, and a timestamp renders with exactly as many fractional
 * digits as its scale and none at all when the scale is zero.
 *
 * <p>Four deliberate differences, each because the reference is wrong rather than different:
 *
 * <ul>
 *   <li><b>A tuple renders as text.</b> clickhouse-jdbc returns {@code [Ljava.lang.Object;@1f2}
 *       from {@code getString} on one, which is {@code String.valueOf} applied to an array.
 *   <li><b>JSON keeps the engine's text.</b> The reference parses it into a {@code HashMap},
 *       which loses the distinction between {@code 1} and {@code "1"} and cannot be handed back
 *       to a query. The text is what the engine sent.
 *   <li><b>An interval has a value.</b> The reference throws from {@code getObject} on one
 *       while rendering {@code P3D} from {@code getString}; the count is a number and is
 *       returned as one, with the ISO-8601 form kept for {@code getString}.
 *   <li><b>A float converts to an integer when it fits.</b> The reference refuses
 *       {@code getLong} on a {@code Float64} outright. JDBC permits the conversion, so it is
 *       done — truncating toward zero, and throwing only when the value will not fit, which is
 *       the case where the old Arrow path silently returned {@code Long.MAX_VALUE}.
 * </ul>
 */
public final class JdbcValues {

    private JdbcValues() {
    }

    /** Which column a failed conversion is about, for the message. */
    public static final class Column {
        private final int index;
        private final String name;
        private final ClickHouseType type;

        public Column(int index, String name, ClickHouseType type) {
            this.index = index;
            this.name = name;
            this.type = type;
        }

        public ClickHouseType type() {
            return type;
        }

        /** Named in a conversion failure, and used by the driver to build one of its own. */
        public String describe() {
            return "column " + index + " (" + name + ", " + type.name() + ")";
        }
    }

    private static SQLException cannot(Column c, String accessor) {
        return new SQLDataException(
                c.describe() + " cannot be read with " + accessor, "22000");
    }

    private static SQLException outOfRange(Column c, Object value, String target) {
        return new SQLDataException(
                c.describe() + " holds " + value + ", which does not fit a Java " + target, "22003");
    }

    // ---------------------------------------------------------------- getObject

    /** What {@code getObject} returns, which is the class {@link JdbcTypeMapping#className} names. */
    public static Object asObject(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        ClickHouseType t = c.type().unwrapped();
        switch (t.kind()) {
            case STRING:
            case FIXED_STRING:
            case JSON:
                return text(v);
            case ENUM8:
            case ENUM16:
                return label(c, t, v);
            case DATE:
            case DATE32:
                return java.sql.Date.valueOf((LocalDate) v);
            case DATETIME:
            case DATETIME64:
                return java.sql.Timestamp.valueOf((LocalDateTime) v);
            case TIME:
            case TIME64:
                // java.sql.Time has no sub-second field, so this truncates -- as the reference
                // does. getString keeps the full value.
                return java.sql.Time.valueOf(((LocalTime) v).withNano(0));
            case ARRAY:
                return new ChdbArray(
                        t.arguments().get(0), (Object[]) nested(t.arguments().get(0), v));
            case POINT:
            case RING:
            case LINESTRING:
            case MULTILINESTRING:
            case POLYGON:
            case MULTIPOLYGON:
                // The primitive double arrays, as the reference returns them.
                return v;
            case DYNAMIC:
            case VARIANT:
                // The decoded value is already whatever the row held, so only the
                // byte[]-means-text convention is left to apply.
                return v instanceof byte[] ? text(v) : v;
            case TUPLE:
            case NESTED:
                return nestedTuple(t, (Object[]) v);
            case MAP:
                return nestedMap(t, v);
            default:
                return v;
        }
    }

    /** Elements of a composite need the same byte[]-to-text treatment as a top-level value. */
    private static Object nested(ClickHouseType element, Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Object[]) {
            Object[] in = (Object[]) v;
            Object[] out = new Object[in.length];
            ClickHouseType e = element.unwrapped();
            for (int i = 0; i < in.length; i++) {
                out[i] = nestedElement(e, in[i]);
            }
            return out;
        }
        return nestedElement(element.unwrapped(), v);
    }

    private static Object nestedElement(ClickHouseType t, Object v) {
        if (v == null) {
            return null;
        }
        switch (t.kind()) {
            case STRING:
            case FIXED_STRING:
            case JSON:
                return v instanceof byte[] ? text(v) : v;
            case ARRAY:
                return nested(t.arguments().get(0), v);
            case TUPLE:
            case NESTED:
                return v instanceof Object[] ? nestedTuple(t, (Object[]) v) : v;
            case MAP:
                return nestedMap(t, v);
            default:
                return v;
        }
    }

    private static Object[] nestedTuple(ClickHouseType t, Object[] values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            ClickHouseType field = i < t.arguments().size() ? t.arguments().get(i).unwrapped() : null;
            out[i] = field == null ? values[i] : nestedElement(field, values[i]);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object nestedMap(ClickHouseType t, Object v) {
        if (!(v instanceof Map)) {
            return v;
        }
        ClickHouseType value = t.arguments().size() > 1 ? t.arguments().get(1).unwrapped() : null;
        Map<Object, Object> in = (Map<Object, Object>) v;
        java.util.LinkedHashMap<Object, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : in.entrySet()) {
            out.put(e.getKey(), value == null ? e.getValue() : nestedElement(value, e.getValue()));
        }
        return out;
    }

    // ---------------------------------------------------------------- getString

    public static String asString(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        ClickHouseType t = c.type().unwrapped();
        switch (t.kind()) {
            case STRING:
            case FIXED_STRING:
            case JSON:
                return text(v);
            case ENUM8:
            case ENUM16:
                return label(c, t, v);
            case DATETIME:
            case DATETIME64:
                return formatDateTime((LocalDateTime) v, t.scale());
            case TIME:
            case TIME64:
                return formatTime((LocalTime) v, t.scale());
            case INTERVAL:
                return isoInterval(t.name(), ((Number) v).longValue());
            case IPV4:
            case IPV6:
                return ((InetAddress) v).getHostAddress();
            default:
                return render(t, v);
        }
    }

    /**
     * The text form of a value inside a composite, or of a composite itself.
     *
     * <p>Follows the reference exactly, quirks included: elements are comma-and-space separated
     * inside an array but bare-comma separated inside a geometry, a string element is quoted
     * where a top-level string is not, and a null element is {@code NULL} rather than
     * {@code null}.
     */
    private static String render(ClickHouseType t, Object v) {
        if (v == null) {
            return "NULL";
        }
        switch (t.kind()) {
            case POINT:
                return point((double[]) v);
            case RING:
            case LINESTRING:
                return joinPoints((double[][]) v);
            case MULTILINESTRING:
            case POLYGON: {
                StringBuilder sb = new StringBuilder("[");
                double[][][] rings = (double[][][]) v;
                for (int i = 0; i < rings.length; i++) {
                    sb.append(i > 0 ? "," : "").append(joinPoints(rings[i]));
                }
                return sb.append(']').toString();
            }
            case MULTIPOLYGON: {
                StringBuilder sb = new StringBuilder("[");
                double[][][][] polys = (double[][][][]) v;
                for (int i = 0; i < polys.length; i++) {
                    sb.append(i > 0 ? "," : "").append(render(ClickHouseType.parse("Polygon"), polys[i]));
                }
                return sb.append(']').toString();
            }
            case ARRAY: {
                ClickHouseType element = t.arguments().get(0);
                StringBuilder sb = new StringBuilder("[");
                Object[] items = (Object[]) v;
                for (int i = 0; i < items.length; i++) {
                    sb.append(i > 0 ? ", " : "").append(element(element, items[i]));
                }
                return sb.append(']').toString();
            }
            case TUPLE:
            case NESTED: {
                // The reference prints an array's identity here. Rendering the tuple the way
                // ClickHouse prints one is the only useful answer.
                StringBuilder sb = new StringBuilder("(");
                Object[] items = (Object[]) v;
                for (int i = 0; i < items.length; i++) {
                    ClickHouseType field = i < t.arguments().size() ? t.arguments().get(i) : null;
                    sb.append(i > 0 ? "," : "").append(field == null ? String.valueOf(items[i]) : element(field, items[i]));
                }
                return sb.append(')').toString();
            }
            case MAP: {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    sb.append(first ? "" : ", ").append(e.getKey()).append('=');
                    Object val = e.getValue();
                    sb.append(val instanceof byte[] ? text(val) : String.valueOf(val));
                    first = false;
                }
                return sb.append('}').toString();
            }
            default:
                return v instanceof byte[] ? text(v) : String.valueOf(v);
        }
    }

    /** An element inside a composite: quoted when it is text, {@code NULL} when it is absent. */
    private static String element(ClickHouseType type, Object v) {
        if (v == null) {
            return "NULL";
        }
        ClickHouseType t = type.unwrapped();
        switch (t.kind()) {
            case STRING:
            case FIXED_STRING:
            case JSON:
                return "'" + text(v) + "'";
            case ENUM8:
            case ENUM16: {
                String name = t.enumNameByValue().get(((Number) v).longValue());
                return name == null ? String.valueOf(v) : "'" + name + "'";
            }
            default:
                return render(t, v);
        }
    }

    /** Package-visible so {@link ChdbArray#toString} renders the same text as getString. */
    static String renderArray(ClickHouseType elementType, Object[] elements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.length; i++) {
            sb.append(i > 0 ? ", " : "").append(element(elementType, elements[i]));
        }
        return sb.append(']').toString();
    }

    private static String point(double[] p) {
        return "(" + p[0] + "," + p[1] + ")";
    }

    private static String joinPoints(double[][] points) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points.length; i++) {
            sb.append(i > 0 ? "," : "").append(point(points[i]));
        }
        return sb.append(']').toString();
    }

    /** {@code yyyy-MM-dd HH:mm:ss} with exactly {@code scale} fractional digits, or none. */
    private static String formatDateTime(LocalDateTime v, int scale) {
        StringBuilder sb = new StringBuilder(29);
        sb.append(String.format("%04d-%02d-%02d %02d:%02d:%02d",
                v.getYear(), v.getMonthValue(), v.getDayOfMonth(),
                v.getHour(), v.getMinute(), v.getSecond()));
        appendFraction(sb, v.getNano(), scale);
        return sb.toString();
    }

    private static String formatTime(LocalTime v, int scale) {
        StringBuilder sb = new StringBuilder(18);
        sb.append(String.format("%02d:%02d:%02d", v.getHour(), v.getMinute(), v.getSecond()));
        appendFraction(sb, v.getNano(), scale);
        return sb.toString();
    }

    private static void appendFraction(StringBuilder sb, int nanos, int scale) {
        // No fraction for a scale of zero, and none for a value that has none: a DateTime64(3)
        // holding exactly midnight prints as "00:00:00", not "00:00:00.000". Matching the
        // reference, and the shorter form is what a person reads more easily anyway.
        if (scale <= 0 || nanos == 0) {
            return;
        }
        int digits = Math.min(scale, 9);
        String all = String.format("%09d", nanos);
        sb.append('.').append(all, 0, digits);
    }

    /** {@code P3D}, {@code PT3H}: the reference's rendering, and the type names the kind. */
    private static String isoInterval(String typeName, long count) {
        String unit = typeName.startsWith("Interval") ? typeName.substring("Interval".length()) : typeName;
        switch (unit) {
            case "Year": return "P" + count + "Y";
            case "Quarter": return "P" + (count * 3) + "M";
            case "Month": return "P" + count + "M";
            case "Week": return "P" + (count * 7) + "D";
            case "Day": return "P" + count + "D";
            case "Hour": return "PT" + count + "H";
            case "Minute": return "PT" + count + "M";
            case "Second": return "PT" + count + "S";
            default:
                // Milli, micro and nanosecond have no whole-second ISO form; the count and its
                // unit is more use than a rounded duration.
                return count + " " + unit.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static String text(Object v) {
        return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : String.valueOf(v);
    }

    private static String label(Column c, ClickHouseType t, Object v) throws SQLException {
        String name = t.enumNameByValue().get(((Number) v).longValue());
        if (name == null) {
            throw new SQLDataException(
                    c.describe() + " holds " + v + ", which is not one of its members", "22000");
        }
        return name;
    }

    // ---------------------------------------------------------------- numbers

    public static long asLong(Column c, Object v) throws SQLException {
        if (v == null) {
            return 0;
        }
        Object n = numericSource(c, v);
        if (n instanceof BigInteger) {
            try {
                return ((BigInteger) n).longValueExact();
            } catch (ArithmeticException e) {
                throw outOfRange(c, n, "long");
            }
        }
        if (n instanceof BigDecimal) {
            try {
                return ((BigDecimal) n).setScale(0, RoundingMode.DOWN).longValueExact();
            } catch (ArithmeticException e) {
                throw outOfRange(c, n, "long");
            }
        }
        if (n instanceof Double || n instanceof Float) {
            double d = ((Number) n).doubleValue();
            if (Double.isNaN(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                throw outOfRange(c, n, "long");
            }
            return (long) d;
        }
        return ((Number) n).longValue();
    }

    public static int asInt(Column c, Object v) throws SQLException {
        long l = asLong(c, v);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw outOfRange(c, l, "int");
        }
        return (int) l;
    }

    public static short asShort(Column c, Object v) throws SQLException {
        long l = asLong(c, v);
        if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
            throw outOfRange(c, l, "short");
        }
        return (short) l;
    }

    public static byte asByte(Column c, Object v) throws SQLException {
        long l = asLong(c, v);
        if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) {
            throw outOfRange(c, l, "byte");
        }
        return (byte) l;
    }

    public static double asDouble(Column c, Object v) throws SQLException {
        if (v == null) {
            return 0;
        }
        Object n = numericSource(c, v);
        return ((Number) n).doubleValue();
    }

    public static float asFloat(Column c, Object v) throws SQLException {
        return (float) asDouble(c, v);
    }

    public static BigDecimal asBigDecimal(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        Object n = numericSource(c, v);
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof BigInteger) {
            return new BigDecimal((BigInteger) n);
        }
        if (n instanceof Double || n instanceof Float) {
            double d = ((Number) n).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                // BigDecimal has no NaN and no infinity, so there is no value to return.
                // Guarded explicitly because BigDecimal.valueOf(double) formats the double
                // first and then fails on the text, which escapes as an unchecked
                // NumberFormatException -- the defect class this layer exists to remove. The
                // differential run against clickhouse-jdbc is what found it; the reference
                // refuses these too, but with a SQLException.
                throw new SQLDataException(
                        c.describe() + " holds " + d + ", which has no BigDecimal value",
                        "22003",
                        0);
            }
            if (n instanceof Float) {
                // The float's own shortest decimal text, not the widened double's:
                // BigDecimal.valueOf((double) 3.4028235E38f) is 3.4028234663852886E+38, which
                // states 17 digits of a value that carries 7. clickhouse-jdbc answers
                // 3.4028235E+38, and it is right.
                return new BigDecimal(Float.toString((Float) n));
            }
            return BigDecimal.valueOf(d);
        }
        return BigDecimal.valueOf(((Number) n).longValue());
    }

    public static boolean asBoolean(Column c, Object v) throws SQLException {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        Object n = numericSource(c, v);
        if (n instanceof BigInteger) {
            return ((BigInteger) n).signum() != 0;
        }
        if (n instanceof BigDecimal) {
            return ((BigDecimal) n).signum() != 0;
        }
        return ((Number) n).doubleValue() != 0;
    }

    /**
     * The number behind a value, or a refusal naming the column.
     *
     * <p>An enum yields its number, which is the half of an enum that {@code getInt} wants.
     * Everything that is not a number at all is refused here rather than coerced, so
     * {@code getInt} on an array is an error and not a surprise.
     */
    private static Object numericSource(Column c, Object v) throws SQLException {
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        if (v instanceof byte[]) {
            // JDBC allows a character column to be read with a numeric accessor when its text
            // is a number, and callers rely on it -- a parameter bound as a string and read
            // back with getInt, for one. Parsed as a BigDecimal so that both "42" and "42.5"
            // work and the range checks above still apply.
            String text = new String((byte[]) v, StandardCharsets.UTF_8).trim();
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new SQLDataException(
                        c.describe() + " holds \"" + text + "\", which is not a number", "22018", 0, e);
            }
        }
        throw cannot(c, "a numeric accessor");
    }

    // ---------------------------------------------------------------- bytes and temporals

    public static byte[] asBytes(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        // getBytes is for binary columns. Inventing an encoding for a number -- which the old
        // path did, returning the UTF-8 of its decimal text -- makes an error look like data.
        throw cannot(c, "getBytes");
    }

    public static java.sql.Date asDate(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate) {
            return java.sql.Date.valueOf((LocalDate) v);
        }
        if (v instanceof LocalDateTime) {
            return java.sql.Date.valueOf(((LocalDateTime) v).toLocalDate());
        }
        throw cannot(c, "getDate");
    }

    public static java.sql.Time asTime(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalTime) {
            return java.sql.Time.valueOf(((LocalTime) v).withNano(0));
        }
        if (v instanceof LocalDateTime) {
            return java.sql.Time.valueOf(((LocalDateTime) v).toLocalTime().withNano(0));
        }
        throw cannot(c, "getTime");
    }

    public static java.sql.Timestamp asTimestamp(Column c, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime) {
            return java.sql.Timestamp.valueOf((LocalDateTime) v);
        }
        if (v instanceof LocalDate) {
            return java.sql.Timestamp.valueOf(((LocalDate) v).atStartOfDay());
        }
        throw cannot(c, "getTimestamp");
    }
}
