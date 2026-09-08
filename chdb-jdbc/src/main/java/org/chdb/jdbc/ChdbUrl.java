package org.chdb.jdbc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A parsed {@code jdbc:chdb:} URL.
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * jdbc:chdb:                        in-memory, same as :memory:
 * jdbc:chdb::memory:                in-memory, explicit
 * jdbc:chdb:/var/lib/chdb           on-disk storage at that path
 * jdbc:chdb:./data?max_threads=4    relative path, plus engine settings
 * </pre>
 *
 * <p>Everything after {@code ?} is {@code key=value} pairs separated by {@code &}, joined
 * with any {@link Properties} passed to {@code DriverManager.getConnection}. The Properties
 * win: a caller that went to the trouble of building them meant them to.
 *
 * <h2>Storage path scope</h2>
 * The engine uses one storage path per process, so the path in the URL is a process-wide
 * commitment, not a per-connection one -- see {@link StoragePathRegistry}. File paths are
 * normalized to an absolute, {@code .}/{@code ..}-free path before they are compared, so {@code ./data} and
 * {@code /home/me/data} are recognized as the same storage rather than fighting over it.
 *
 * <p>{@code :memory:} is one shared in-process database, not a private one per Connection.
 * Two {@code jdbc:chdb::memory:} connections in a JVM see each other's tables, and the data
 * lives until the last of them closes. That follows from the engine's one-path-per-process
 * model; a driver cannot give each Connection its own.
 */
public final class ChdbUrl {

    /** URL prefix this driver accepts. */
    public static final String PREFIX = "jdbc:chdb:";

    /** The in-memory storage path. */
    public static final String MEMORY = ":memory:";

    // Properties the driver consumes itself. Everything else is forwarded to the engine as
    // --<key>=<value>, which is how chdb_connect takes ClickHouse settings.
    static final String PROP_LOW_CARDINALITY_AS_DICTIONARY = "lowCardinalityAsDictionary";
    static final String PROP_UNSUPPORTED_AS_BINARY = "unsupportedAsBinary";
    static final String PROP_STRING_AS_STRING = "stringAsString";

    private static final List<String> DRIVER_PROPERTIES =
            Collections.unmodifiableList(
                    java.util.Arrays.asList(
                            PROP_LOW_CARDINALITY_AS_DICTIONARY,
                            PROP_UNSUPPORTED_AS_BINARY,
                            PROP_STRING_AS_STRING));

    private final String url;
    private final String rawPath;
    private final boolean memory;
    private final Path storagePath;
    private final Map<String, String> properties;

    private ChdbUrl(
            String url, String rawPath, boolean memory, Path storagePath, Map<String, String> properties) {
        this.url = url;
        this.rawPath = rawPath;
        this.memory = memory;
        this.storagePath = storagePath;
        this.properties = Collections.unmodifiableMap(properties);
    }

    /** Whether this driver handles the given URL. */
    public static boolean accepts(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /**
     * Parses a URL and merges in caller-supplied properties.
     *
     * @throws SQLException if the URL is not a {@code jdbc:chdb:} URL, or its path cannot be
     *     resolved
     */
    public static ChdbUrl parse(String url, Properties supplied) throws SQLException {
        if (!accepts(url)) {
            throw new SQLException(
                    "Not a chDB JDBC URL: " + url + ". Expected " + PREFIX
                            + "<path>, " + PREFIX + MEMORY + ", or " + PREFIX + " for in-memory.",
                    "08001");
        }

        String remainder = url.substring(PREFIX.length());
        String pathPart = remainder;
        String queryPart = null;
        int question = remainder.indexOf('?');
        if (question >= 0) {
            pathPart = remainder.substring(0, question);
            queryPart = remainder.substring(question + 1);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        if (queryPart != null && !queryPart.isEmpty()) {
            parseQuery(url, queryPart, properties);
        }
        if (supplied != null) {
            for (String name : supplied.stringPropertyNames()) {
                properties.put(name, supplied.getProperty(name));
            }
        }

        pathPart = pathPart.trim();
        if (pathPart.isEmpty() || MEMORY.equals(pathPart)) {
            return new ChdbUrl(url, MEMORY, true, null, properties);
        }

        Path resolved;
        try {
            // Absolute + normalized, but deliberately not toRealPath(): the directory may not
            // exist yet, and the engine is what creates it. normalize() collapses ".." and
            // "." textually, which is enough to make two spellings of one path compare equal.
            resolved = Paths.get(pathPart).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new SQLException(
                    "Cannot resolve the storage path \"" + pathPart + "\" from URL " + url + ": " + e,
                    "08001",
                    e);
        }
        return new ChdbUrl(url, pathPart, false, resolved, properties);
    }

    private static void parseQuery(String url, String query, Map<String, String> into) throws SQLException {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new SQLException(
                        "Malformed property \"" + pair + "\" in URL " + url
                                + ". Properties are key=value pairs separated by '&'.",
                        "08001");
            }
            into.put(decode(url, pair.substring(0, eq)), decode(url, pair.substring(eq + 1)));
        }
    }

    private static String decode(String url, String value) throws SQLException {
        try {
            // URLDecoder(String, String) rather than the Charset overload, which is Java 10+;
            // V1's floor is Java 11 but this keeps the file compilable on the old signature.
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new SQLException("UTF-8 is unavailable in this JVM", "08001", e);
        } catch (IllegalArgumentException e) {
            throw new SQLException(
                    "Malformed percent-encoding in URL " + url + ": " + e.getMessage(), "08001", e);
        }
    }

    /** The original URL. */
    public String url() {
        return url;
    }

    /** Whether this is the in-memory database. */
    public boolean isMemory() {
        return memory;
    }

    /** Storage path as written in the URL, or {@code :memory:}. */
    public String rawPath() {
        return rawPath;
    }

    /** Absolute normalized storage path, or null for {@code :memory:}. */
    public Path storagePath() {
        return storagePath;
    }

    /** The key {@link StoragePathRegistry} compares connections by. */
    public String registryKey() {
        return memory ? MEMORY : storagePath.toString();
    }

    /** All merged properties, driver and engine alike. */
    public Map<String, String> properties() {
        return properties;
    }

    boolean booleanProperty(String name, boolean fallback) {
        String value = properties.get(name);
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    /**
     * The argument vector for {@code chdb_connect}.
     *
     * <p>{@code argv[0]} is the program name the engine expects, then {@code --path}, then
     * every non-driver property as {@code --key=value}. Forwarding unknown properties rather
     * than rejecting them is deliberate: it makes every ClickHouse query setting reachable
     * from a JDBC URL without this driver having to enumerate them, and the engine already
     * fails a connection with an invalid value for a setting it knows.
     */
    List<String> toConnectArguments() {
        List<String> arguments = new ArrayList<>();
        arguments.add("clickhouse");
        // ":memory:" is the engine's default and must NOT be passed as --path. The engine does
        // not treat the string specially in that position: it takes it as a directory name and
        // creates a literal ":memory:" directory in the process's working directory, turning an
        // in-memory database into an on-disk one that outlives the JVM. chdb-core's own ADBC
        // driver omits it for the same reason (chdb-adbc.cpp, "must NOT be passed as --path").
        if (!memory) {
            arguments.add("--path=" + storagePath.toString());
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (DRIVER_PROPERTIES.contains(entry.getKey())) {
                continue;
            }
            arguments.add("--" + entry.getKey() + "=" + entry.getValue());
        }
        return arguments;
    }

    /** Property names the driver handles itself, for {@code getPropertyInfo}. */
    static List<String> driverPropertyNames() {
        return DRIVER_PROPERTIES;
    }
}
