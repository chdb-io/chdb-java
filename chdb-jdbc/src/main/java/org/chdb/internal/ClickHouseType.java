package org.chdb.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ClickHouse type, parsed from the name the engine writes in a {@code WithNamesAndTypes}
 * header.
 *
 * <h2>Why this exists</h2>
 * The Arrow path this replaces typed a column from the Arrow schema, which is a lossy
 * projection of ClickHouse's type system made by the engine's own writer: {@code Enum8} arrives
 * as {@code Int8} with the labels gone, {@code Int128}, {@code IPv6} and {@code UUID} all
 * arrive as 16 bytes of fixed-size binary, {@code DateTime} arrives as {@code UInt32}, and
 * {@code Nullable} and {@code LowCardinality} are erased from the name. Reading
 * {@code RowBinaryWithNamesAndTypes} instead means the type arrives as the engine declared it,
 * and this class is what turns that string back into something to decode and describe with.
 *
 * <h2>Shape</h2>
 * Immutable, and a tree: {@code Nullable(Array(Tuple(a Int32, b String)))} is four nodes.
 * Parameters that matter to decoding or to {@code ResultSetMetaData} are pulled out as fields
 * rather than left in the string — {@link #scale()} for a {@code Decimal} or
 * {@code DateTime64}, {@link #fixedLength()} for a {@code FixedString}, {@link #timeZone()},
 * and the enum members.
 *
 * <p>An unrecognised type is not an error. It parses as {@link Kind#UNKNOWN} keeping its full
 * name, so metadata can still report what the engine said and only the attempt to decode it
 * fails. A new ClickHouse type should degrade to "cannot read this column", never to "cannot
 * run this query".
 */
public final class ClickHouseType {

    /** What a type is, for decoding and for the JDBC mapping. */
    public enum Kind {
        INT8, INT16, INT32, INT64, INT128, INT256,
        UINT8, UINT16, UINT32, UINT64, UINT128, UINT256,
        FLOAT32, FLOAT64, BFLOAT16,
        BOOL,
        STRING, FIXED_STRING,
        ENUM8, ENUM16,
        DECIMAL,
        DATE, DATE32, DATETIME, DATETIME64, TIME, TIME64,
        UUID, IPV4, IPV6,
        INTERVAL,
        NULLABLE, LOW_CARDINALITY,
        ARRAY, TUPLE, MAP, NESTED, VARIANT,
        JSON, DYNAMIC,
        POINT, RING, LINESTRING, MULTILINESTRING, POLYGON, MULTIPOLYGON,
        AGGREGATE_FUNCTION, SIMPLE_AGGREGATE_FUNCTION,
        NOTHING,
        UNKNOWN,
    }

    private final Kind kind;
    private final String name;
    private final List<ClickHouseType> arguments;
    private final List<String> fieldNames;
    private final int precision;
    private final int scale;
    private final int fixedLength;
    private final String timeZone;
    private final Map<String, Long> enumValueByName;
    private final Map<Long, String> enumNameByValue;

    private ClickHouseType(Builder b) {
        this.kind = b.kind;
        this.name = b.name;
        this.arguments = b.arguments == null ? Collections.emptyList()
                : Collections.unmodifiableList(b.arguments);
        this.fieldNames = b.fieldNames == null ? Collections.emptyList()
                : Collections.unmodifiableList(b.fieldNames);
        this.precision = b.precision;
        this.scale = b.scale;
        this.fixedLength = b.fixedLength;
        this.timeZone = b.timeZone;
        this.enumValueByName = b.enumValueByName == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(b.enumValueByName);
        this.enumNameByValue = b.enumNameByValue == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(b.enumNameByValue);
    }

    /** The type exactly as the engine named it, nesting and parameters included. */
    public String name() { return name; }

    public Kind kind() { return kind; }

    /** Element types: one for {@code Array} and {@code Nullable}, two for {@code Map}, n for {@code Tuple}. */
    public List<ClickHouseType> arguments() { return arguments; }

    /** Tuple or Nested field names, empty when the tuple is positional. */
    public List<String> fieldNames() { return fieldNames; }

    /** Decimal digits, or 0 where the concept does not apply. */
    public int precision() { return precision; }

    /** Decimal or sub-second scale, or 0. */
    public int scale() { return scale; }

    /** {@code FixedString(N)}'s N, or 0. */
    public int fixedLength() { return fixedLength; }

    /** The declared timezone of a {@code DateTime}/{@code DateTime64}, or null when implicit. */
    public String timeZone() { return timeZone; }

    /** Enum members by label. Empty for every other type. */
    public Map<String, Long> enumValueByName() { return enumValueByName; }

    /** Enum members by number, which is what the wire carries. */
    public Map<Long, String> enumNameByValue() { return enumNameByValue; }

    /**
     * This type with {@code Nullable} and {@code LowCardinality} peeled off.
     *
     * <p>Both are wrappers over another type rather than types of their own: they change how a
     * value is framed on the wire, not what the value is. Decoding handles the framing and then
     * asks this for what to decode.
     */
    public ClickHouseType unwrapped() {
        ClickHouseType t = this;
        while (t.kind == Kind.NULLABLE || t.kind == Kind.LOW_CARDINALITY) {
            if (t.arguments.isEmpty()) {
                return t;
            }
            t = t.arguments.get(0);
        }
        return t;
    }

    /** Whether a NULL can appear in this column, at any depth of wrapping. */
    public boolean isNullable() {
        ClickHouseType t = this;
        while (true) {
            if (t.kind == Kind.NULLABLE) {
                return true;
            }
            if (t.kind == Kind.LOW_CARDINALITY && !t.arguments.isEmpty()) {
                t = t.arguments.get(0);
                continue;
            }
            return false;
        }
    }

    @Override
    public String toString() { return name; }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses a type name.
     *
     * @throws IllegalArgumentException only for a name that is not well-formed at all, such as
     *     unbalanced parentheses. An unrecognised but well-formed name yields {@link Kind#UNKNOWN}.
     */
    public static ClickHouseType parse(String type) {
        if (type == null) {
            throw new IllegalArgumentException("type name is null");
        }
        Parser p = new Parser(type);
        ClickHouseType parsed = p.parseType();
        p.skipSpaces();
        if (!p.atEnd()) {
            throw new IllegalArgumentException(
                    "trailing text in type name \"" + type + "\" at offset " + p.position());
        }
        return parsed;
    }

    /**
     * A type whose name could not be parsed at all.
     *
     * <p>Kept so a malformed or unimaginable type name costs the caller that column rather than
     * the statement, and so metadata can still report what the engine said.
     */
    public static ClickHouseType unknown(String name) {
        Builder b = new Builder();
        b.kind = Kind.UNKNOWN;
        b.name = name;
        return b.build();
    }

    private static final class Builder {
        Kind kind = Kind.UNKNOWN;
        String name;
        List<ClickHouseType> arguments;
        List<String> fieldNames;
        int precision;
        int scale;
        int fixedLength;
        String timeZone;
        Map<String, Long> enumValueByName;
        Map<Long, String> enumNameByValue;

        ClickHouseType build() { return new ClickHouseType(this); }
    }

    /**
     * A hand-written recursive descent parser rather than a regular expression.
     *
     * <p>Enum member labels are arbitrary quoted strings that may contain commas, parentheses
     * and escaped quotes — {@code Enum8('a,b' = 1, ')' = 2)} is a legal type — so the grammar
     * is not regular and matching it with a pattern would be wrong in ways that only show up on
     * someone else's data.
     */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        int position() { return i; }

        boolean atEnd() { return i >= s.length(); }

        void skipSpaces() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        ClickHouseType parseType() {
            skipSpaces();
            int start = i;
            String ident = parseIdentifier();
            List<String> rawArgs = new ArrayList<>();
            List<ClickHouseType> typeArgs = null;
            skipSpaces();
            if (i < s.length() && s.charAt(i) == '(') {
                rawArgs = parseArgumentList();
            }
            String full = s.substring(start, i);

            Builder b = new Builder();
            b.name = full;
            classify(b, ident, rawArgs);
            if (b.arguments == null && typeArgs != null) {
                b.arguments = typeArgs;
            }
            return b.build();
        }

        private String parseIdentifier() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    i++;
                } else {
                    break;
                }
            }
            return s.substring(start, i);
        }

        /** The comma-separated contents of one parenthesised group, still as text. */
        private List<String> parseArgumentList() {
            expect('(');
            List<String> out = new ArrayList<>();
            skipSpaces();
            if (i < s.length() && s.charAt(i) == ')') {
                i++;
                return out;
            }
            int depth = 0;
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\'') {
                    skipQuoted();
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        out.add(s.substring(start, i).trim());
                        i++;
                        return out;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    out.add(s.substring(start, i).trim());
                    i++;
                    skipSpaces();
                    start = i;
                    continue;
                }
                i++;
            }
            throw new IllegalArgumentException("unbalanced parentheses in type name \"" + s + "\"");
        }

        /** Advances past a single-quoted literal, honouring backslash and doubled-quote escapes. */
        private void skipQuoted() {
            expect('\'');
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    // '' inside a literal is an escaped quote, not the end.
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    i++;
                    return;
                }
                i++;
            }
            throw new IllegalArgumentException("unterminated quoted literal in \"" + s + "\"");
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException(
                        "expected '" + c + "' at offset " + i + " in \"" + s + "\"");
            }
            i++;
        }
    }

    // ------------------------------------------------------------------ classification

    private static void classify(Builder b, String ident, List<String> args) {
        switch (ident) {
            case "Int8": b.kind = Kind.INT8; return;
            case "Int16": b.kind = Kind.INT16; return;
            case "Int32": b.kind = Kind.INT32; return;
            case "Int64": b.kind = Kind.INT64; return;
            case "Int128": b.kind = Kind.INT128; return;
            case "Int256": b.kind = Kind.INT256; return;
            case "UInt8": b.kind = Kind.UINT8; return;
            case "UInt16": b.kind = Kind.UINT16; return;
            case "UInt32": b.kind = Kind.UINT32; return;
            case "UInt64": b.kind = Kind.UINT64; return;
            case "UInt128": b.kind = Kind.UINT128; return;
            case "UInt256": b.kind = Kind.UINT256; return;
            case "Float32": b.kind = Kind.FLOAT32; return;
            case "Float64": b.kind = Kind.FLOAT64; return;
            case "BFloat16": b.kind = Kind.BFLOAT16; return;
            case "Bool":
            case "Boolean": b.kind = Kind.BOOL; return;
            case "String": b.kind = Kind.STRING; return;
            case "UUID": b.kind = Kind.UUID; return;
            case "IPv4": b.kind = Kind.IPV4; return;
            case "IPv6": b.kind = Kind.IPV6; return;
            case "Date": b.kind = Kind.DATE; return;
            case "Date32": b.kind = Kind.DATE32; return;
            case "JSON":
            case "Object": b.kind = Kind.JSON; return;
            case "Dynamic": b.kind = Kind.DYNAMIC; return;
            case "Nothing": b.kind = Kind.NOTHING; return;
            case "Point": b.kind = Kind.POINT; return;
            case "Ring": b.kind = Kind.RING; return;
            case "LineString": b.kind = Kind.LINESTRING; return;
            case "MultiLineString": b.kind = Kind.MULTILINESTRING; return;
            case "Polygon": b.kind = Kind.POLYGON; return;
            case "MultiPolygon": b.kind = Kind.MULTIPOLYGON; return;

            case "FixedString":
                b.kind = Kind.FIXED_STRING;
                b.fixedLength = intArg(args, 0, ident);
                return;

            case "Enum8":
            case "Enum16":
                b.kind = "Enum8".equals(ident) ? Kind.ENUM8 : Kind.ENUM16;
                parseEnumMembers(b, args);
                return;

            case "Decimal":
                b.kind = Kind.DECIMAL;
                // Decimal(P, S); Decimal(S) is not a form ClickHouse emits in a header.
                b.precision = intArg(args, 0, ident);
                b.scale = args.size() > 1 ? intArg(args, 1, ident) : 0;
                return;
            case "Decimal32":
            case "Decimal64":
            case "Decimal128":
            case "Decimal256":
                b.kind = Kind.DECIMAL;
                // The width is in the name and fixes the precision; only the scale is a parameter.
                b.precision = "Decimal32".equals(ident) ? 9
                        : "Decimal64".equals(ident) ? 18
                        : "Decimal128".equals(ident) ? 38 : 76;
                b.scale = intArg(args, 0, ident);
                return;

            case "DateTime":
                b.kind = Kind.DATETIME;
                b.timeZone = args.isEmpty() ? null : unquote(args.get(0));
                return;
            case "DateTime64":
                b.kind = Kind.DATETIME64;
                b.scale = args.isEmpty() ? 3 : intArg(args, 0, ident);
                b.timeZone = args.size() > 1 ? unquote(args.get(1)) : null;
                return;
            case "Time":
                b.kind = Kind.TIME;
                return;
            case "Time64":
                b.kind = Kind.TIME64;
                b.scale = args.isEmpty() ? 3 : intArg(args, 0, ident);
                return;

            case "Nullable":
                b.kind = Kind.NULLABLE;
                b.arguments = parseAll(args);
                return;
            case "LowCardinality":
                b.kind = Kind.LOW_CARDINALITY;
                b.arguments = parseAll(args);
                return;
            case "Array":
                b.kind = Kind.ARRAY;
                b.arguments = parseAll(args);
                return;
            case "Map":
                b.kind = Kind.MAP;
                b.arguments = parseAll(args);
                return;
            case "Variant":
                b.kind = Kind.VARIANT;
                b.arguments = parseAll(args);
                return;
            case "Tuple":
            case "Nested": {
                b.kind = "Tuple".equals(ident) ? Kind.TUPLE : Kind.NESTED;
                List<ClickHouseType> types = new ArrayList<>(args.size());
                List<String> names = new ArrayList<>(args.size());
                boolean named = false;
                for (String arg : args) {
                    // "name Type" when the tuple is named, bare "Type" when positional. Split on
                    // the first space that is not inside quotes or parentheses.
                    int cut = splitFieldName(arg);
                    if (cut > 0) {
                        names.add(unquote(arg.substring(0, cut).trim()));
                        types.add(parse(arg.substring(cut).trim()));
                        named = true;
                    } else {
                        names.add(null);
                        types.add(parse(arg));
                    }
                }
                b.arguments = types;
                b.fieldNames = named ? names : null;
                return;
            }

            case "SimpleAggregateFunction":
            case "AggregateFunction": {
                b.kind = "SimpleAggregateFunction".equals(ident)
                        ? Kind.SIMPLE_AGGREGATE_FUNCTION : Kind.AGGREGATE_FUNCTION;
                // First argument is the function, the rest are its argument types.
                List<ClickHouseType> types = new ArrayList<>();
                for (int n = 1; n < args.size(); n++) {
                    types.add(parse(args.get(n)));
                }
                b.arguments = types;
                return;
            }

            default:
                if (ident.startsWith("Interval")) {
                    b.kind = Kind.INTERVAL;
                    return;
                }
                b.kind = Kind.UNKNOWN;
        }
    }

    /**
     * The offset of the space separating a tuple field's name from its type, or -1 when the
     * element is a bare type.
     *
     * <p>Scanning rather than splitting on the first space: {@code Tuple(Map(String, Int32))}
     * has a space in it and no field name, and a quoted field name may contain one.
     */
    private static int splitFieldName(String arg) {
        int depth = 0;
        for (int n = 0; n < arg.length(); n++) {
            char c = arg.charAt(n);
            if (c == '\'') {
                n = skipQuotedFrom(arg, n);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                return n;
            }
        }
        return -1;
    }

    private static int skipQuotedFrom(String s, int from) {
        int n = from + 1;
        while (n < s.length()) {
            char c = s.charAt(n);
            if (c == '\\') {
                n += 2;
                continue;
            }
            if (c == '\'') {
                if (n + 1 < s.length() && s.charAt(n + 1) == '\'') {
                    n += 2;
                    continue;
                }
                return n;
            }
            n++;
        }
        return s.length();
    }

    private static List<ClickHouseType> parseAll(List<String> args) {
        List<ClickHouseType> out = new ArrayList<>(args.size());
        for (String a : args) {
            out.add(parse(a));
        }
        return out;
    }

    private static void parseEnumMembers(Builder b, List<String> args) {
        Map<String, Long> byName = new LinkedHashMap<>();
        Map<Long, String> byValue = new LinkedHashMap<>();
        for (String arg : args) {
            int eq = arg.lastIndexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("enum member without a value: \"" + arg + "\"");
            }
            String label = unquote(arg.substring(0, eq).trim());
            long value;
            try {
                value = Long.parseLong(arg.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("enum member value is not a number: \"" + arg + "\"", e);
            }
            byName.put(label, value);
            // First label wins for a repeated value; ClickHouse forbids duplicates anyway.
            byValue.putIfAbsent(value, label);
        }
        b.enumValueByName = byName;
        b.enumNameByValue = byValue;
    }

    private static int intArg(List<String> args, int index, String ident) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(ident + " is missing argument " + (index + 1));
        }
        try {
            return Integer.parseInt(args.get(index).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    ident + " argument " + (index + 1) + " is not a number: \"" + args.get(index) + "\"", e);
        }
    }

    /** Strips the single quotes ClickHouse puts round labels and timezones, undoing escapes. */
    private static String unquote(String raw) {
        String t = raw.trim();
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            String inner = t.substring(1, t.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int n = 0; n < inner.length(); n++) {
                char c = inner.charAt(n);
                if (c == '\\' && n + 1 < inner.length()) {
                    sb.append(inner.charAt(++n));
                } else if (c == '\'' && n + 1 < inner.length() && inner.charAt(n + 1) == '\'') {
                    sb.append('\'');
                    n++;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return t;
    }
}
