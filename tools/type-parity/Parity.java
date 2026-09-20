import java.sql.*;
import java.util.*;

/**
 * Reads the same expression through chdb-jdbc and clickhouse-jdbc (v2) and reports where they
 * behave differently.
 *
 * Both drivers live in one JVM -- chdb-jdbc owns jdbc:chdb:, clickhouse-jdbc owns
 * jdbc:clickhouse: -- and the server is pinned to the same version the chDB engine is built
 * from, so a difference is the driver's rather than the engine's.
 *
 * Exception *messages* are deliberately not compared: two drivers wording a refusal
 * differently is not a finding. What is compared is the shape of the answer -- a value, a
 * SQLException, or an unchecked throw, which for a JDBC accessor is a conformance bug.
 */
public class Parity {

    enum Kind { VALUE, SQL_EXCEPTION, UNCHECKED }

    static final class R {
        final Kind kind; final String value; final String exClass; final String message;
        R(Kind k, String v, String c, String m) { kind = k; value = v; exClass = c; message = m; }
        String brief() {
            return kind == Kind.VALUE ? value : "!" + exClass + (message == null ? "" : ": " + message);
        }
    }

    static String show(Object o) {
        if (o == null) return "null";
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o; StringBuilder sb = new StringBuilder("0x");
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
        if (o.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) sb.append(i > 0 ? ", " : "").append(show(java.lang.reflect.Array.get(o, i)));
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }

    interface Get { Object get() throws Exception; }

    static R probe(Get g) {
        try {
            Object v = g.get();
            return new R(Kind.VALUE, show(v) + (v == null ? "" : " <" + v.getClass().getSimpleName() + ">"), null, null);
        } catch (Throwable t) {
            Throwable r = t;
            // Unwrap only reflective/wrapper layers, not a driver's own wrapping: the outermost
            // driver exception is what a caller catches.
            String cls = r.getClass().getName();
            Kind k = (r instanceof SQLException) ? Kind.SQL_EXCEPTION : Kind.UNCHECKED;
            String m = String.valueOf(r.getMessage());
            if (m.length() > 70) m = m.substring(0, 70) + "…";
            return new R(k, null, r.getClass().getSimpleName(), m.replace('\n', ' '));
        }
    }

    static final String[] ACCESSORS = {
        "meta.typeName", "meta.jdbcType", "meta.className", "meta.precision", "meta.scale",
        "meta.nullable", "meta.signed",
        "getObject", "getString", "getBoolean", "getInt", "getLong", "getDouble",
        "getBigDecimal", "getBytes", "getDate", "getTime", "getTimestamp", "wasNull",
    };

    static Map<String, R> read(Connection c, String sql, String[] queryError) {
        Map<String, R> out = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            out.put("meta.typeName", probe(() -> md.getColumnTypeName(1)));
            out.put("meta.jdbcType", probe(() -> {
                int t = md.getColumnType(1);
                JDBCType j = null;
                try { j = JDBCType.valueOf(t); } catch (RuntimeException ignored) { }
                return (j == null ? "?" : j.getName()) + "(" + t + ")";
            }));
            out.put("meta.className", probe(() -> md.getColumnClassName(1)));
            out.put("meta.precision", probe(() -> md.getPrecision(1)));
            out.put("meta.scale", probe(() -> md.getScale(1)));
            out.put("meta.nullable", probe(() -> md.isNullable(1)));
            out.put("meta.signed", probe(() -> md.isSigned(1)));
            if (!rs.next()) { queryError[0] = "no row"; return out; }
            out.put("getObject", probe(() -> rs.getObject(1)));
            out.put("getString", probe(() -> rs.getString(1)));
            out.put("getBoolean", probe(() -> rs.getBoolean(1)));
            out.put("getInt", probe(() -> rs.getInt(1)));
            out.put("getLong", probe(() -> rs.getLong(1)));
            out.put("getDouble", probe(() -> rs.getDouble(1)));
            out.put("getBigDecimal", probe(() -> rs.getBigDecimal(1)));
            out.put("getBytes", probe(() -> rs.getBytes(1)));
            out.put("getDate", probe(() -> rs.getDate(1)));
            out.put("getTime", probe(() -> rs.getTime(1)));
            out.put("getTimestamp", probe(() -> rs.getTimestamp(1)));
            out.put("wasNull", probe(() -> { rs.getObject(1); return rs.wasNull(); }));
        } catch (Throwable t) {
            Throwable r = t;
            String m = String.valueOf(r.getMessage());
            if (m.length() > 100) m = m.substring(0, 100) + "…";
            queryError[0] = r.getClass().getSimpleName() + ": " + m.replace('\n', ' ');
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", System.getProperty("ch.user", "parity"));
        p.setProperty("password", System.getProperty("ch.password", "parity"));

        List<String[]> cases = Cases.all();
        int identical = 0, unsupportedBoth = 0;
        List<String> valueDiffs = new ArrayList<>();
        List<String> shapeDiffs = new ArrayList<>();
        // accessor -> cases where chdb threw unchecked and clickhouse-jdbc threw SQLException
        Map<String, List<String>> uncheckedLeak = new TreeMap<>();
        Map<String, List<String>> chdbOnlyRefuses = new TreeMap<>();
        Map<String, List<String>> chOnlyRefuses = new TreeMap<>();
        List<String> queryLevel = new ArrayList<>();

        try (Connection chdb = DriverManager.getConnection("jdbc:chdb::memory:");
                Connection ch = DriverManager.getConnection(System.getProperty("ch.url"), p)) {
            System.out.println("chdb-jdbc " + chdb.getMetaData().getDriverVersion()
                    + " vs clickhouse-jdbc " + ch.getMetaData().getDriverVersion()
                    + " on server " + ch.getMetaData().getDatabaseProductVersion()
                    + "  (" + cases.size() + " cases)\n");

            for (String[] c : cases) {
                String label = c[0], sql = "SELECT " + c[1] + " AS v";
                String[] ea = {null}, eb = {null};
                Map<String, R> a = read(chdb, sql, ea);
                Map<String, R> b = read(ch, sql, eb);

                if (ea[0] != null || eb[0] != null) {
                    if (ea[0] != null && eb[0] != null) { unsupportedBoth++; continue; }
                    queryLevel.add(String.format("%-26s chdb=%s | ch=%s", label,
                            ea[0] == null ? "ok" : ea[0], eb[0] == null ? "ok" : eb[0]));
                    continue;
                }

                boolean any = false;
                for (String k : ACCESSORS) {
                    R ra = a.get(k), rb = b.get(k);
                    if (ra == null || rb == null) continue;
                    if (ra.kind == Kind.VALUE && rb.kind == Kind.VALUE) {
                        if (!Objects.equals(ra.value, rb.value)) {
                            any = true;
                            valueDiffs.add(String.format("%-26s %-15s chdb=%s%n%-26s %-15s   ch=%s",
                                    label, k, ra.value, "", "", rb.value));
                        }
                    } else if (ra.kind != Kind.VALUE && rb.kind != Kind.VALUE) {
                        if (ra.kind == Kind.UNCHECKED && rb.kind == Kind.SQL_EXCEPTION) {
                            any = true;
                            uncheckedLeak.computeIfAbsent(k, x -> new ArrayList<>()).add(label + " (" + ra.exClass + ")");
                        }
                    } else if (ra.kind == Kind.VALUE) {
                        any = true;
                        chOnlyRefuses.computeIfAbsent(k, x -> new ArrayList<>()).add(label + " chdb=" + ra.value + " | ch !" + rb.exClass);
                    } else {
                        any = true;
                        chdbOnlyRefuses.computeIfAbsent(k, x -> new ArrayList<>()).add(label + " ch=" + rb.value + " | chdb !" + ra.exClass + ": " + ra.message);
                    }
                }
                if (!any) identical++;
            }
        }

        section("A. The query itself behaves differently", queryLevel);
        section("B. Both return a value, and the values differ", valueDiffs);
        sectionMap("C. chdb-jdbc refuses what clickhouse-jdbc answers", chdbOnlyRefuses);
        sectionMap("D. chdb-jdbc answers what clickhouse-jdbc refuses", chOnlyRefuses);
        sectionMap("E. Both refuse, but chdb-jdbc throws an unchecked exception", uncheckedLeak);
        System.out.printf("%nfully identical cases=%d  unsupported by both=%d%n", identical, unsupportedBoth);
    }

    static void section(String title, List<String> lines) {
        System.out.println("======== " + title + "  (" + lines.size() + ")");
        for (String l : lines) System.out.println(l);
        System.out.println();
    }

    static void sectionMap(String title, Map<String, List<String>> m) {
        int n = m.values().stream().mapToInt(List::size).sum();
        System.out.println("======== " + title + "  (" + n + " across " + m.size() + " accessors)");
        for (Map.Entry<String, List<String>> e : m.entrySet()) {
            System.out.println("  " + e.getKey() + "  (" + e.getValue().size() + ")");
            for (String s : e.getValue()) System.out.println("      " + s);
        }
        System.out.println();
    }
}
