package org.chdb.jdbc;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
 * {@code /home/me/data} are recognized as the same storage rather than fighting over it. The
 * normalization is textual on purpose: it never touches the filesystem, so it works for a
 * directory that does not exist yet, and it works on a JVM whose {@code sun.jnu.encoding}
 * cannot even hold the name.
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
    private final String resolvedPath;
    private final Path storagePath;
    private final Map<String, String> properties;

    private ChdbUrl(
            String url,
            String rawPath,
            boolean memory,
            String resolvedPath,
            Path storagePath,
            Map<String, String> properties) {
        this.url = url;
        this.rawPath = rawPath;
        this.memory = memory;
        this.resolvedPath = resolvedPath;
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
            return new ChdbUrl(url, MEMORY, true, MEMORY, null, properties);
        }

        String resolvedPath;
        Path resolved = null;
        try {
            // Absolute + normalized, but deliberately not toRealPath(): the directory may not
            // exist yet, and the engine is what creates it. normalize() collapses ".." and
            // "." textually, which is enough to make two spellings of one path compare equal.
            resolved = Paths.get(pathPart).toAbsolutePath().normalize();
            resolvedPath = resolved.toString();
        } catch (RuntimeException e) {
            // Paths.get encodes with sun.jnu.encoding, so a name the OS locale cannot express
            // is refused here before anything has looked at a filesystem. That refusal used to
            // sink the whole connection, and it did not have to: resolvedPath exists only as a
            // comparison key for the one-storage-path-per-JVM rule, the path reaches the engine
            // as UTF-8 bytes, and the engine does its own mkdir. Measured in almalinux:8 -- the
            // same URL works in the same container under LANG=C.UTF-8, directory correctly
            // named on disk. So when the only thing wrong is the encoding, do the same
            // absolutise-and-normalise as text; normalize() is a text operation already.
            String textual =
                    encodingThatCannotHold(pathPart) == null ? null : resolveTextually(pathPart);
            if (textual == null) {
                throw new SQLException(
                        "Cannot resolve the storage path \"" + pathPart + "\" from URL " + url
                                + ": " + e + localeHint(pathPart),
                        "08001",
                        e);
            }
            resolvedPath = textual;
        }
        return new ChdbUrl(url, pathPart, false, resolvedPath, resolved, properties);
    }

    /**
     * Absolutises and normalises a path as text, without {@code java.nio.file}.
     *
     * <p>The result must equal {@code Paths.get(path).toAbsolutePath().normalize().toString()}
     * for every path the platform can encode -- {@code ChdbUrlTest} asserts that differentially
     * over a sample including random ones. Getting it wrong is not a cosmetic bug: the
     * storage-path registry would either refuse a second connection to the same directory or
     * admit one to a different directory.
     *
     * <p>Returns null when it cannot answer, in which case the caller refuses the URL rather
     * than guessing.
     */
    static String resolveTextually(String path) {
        // Only for a single-separator filesystem with no drive letters or UNC prefixes. Windows
        // path syntax is not worth hand-rolling, and does not need to be: Windows holds names
        // as UTF-16 and Paths.get there does not fail to encode one. Note that "\" is an
        // ordinary character in a name on such a filesystem, so Windows-style input stays a
        // single name element -- which is exactly what Paths.get does with it on Unix.
        if (!"/".equals(File.separator)) {
            return null;
        }
        // A NUL would truncate the C string the engine is handed, so such a path stays refused.
        // Paths.get rejects it too, but a path that is both unencodable and NUL-bearing throws
        // for the encoding, and the caller only consults this method after an encoding failure
        // -- so the NUL has to be caught here or not at all.
        if (path.indexOf('\0') >= 0) {
            return null;
        }
        String absolute = path;
        if (!path.startsWith("/")) {
            // Same source as UnixFileSystem's default directory, which is what toAbsolutePath()
            // prepends. Duplicate separators are collapsed below, so a working directory of "/"
            // needs no special case.
            String workingDirectory = System.getProperty("user.dir");
            if (workingDirectory == null || !workingDirectory.startsWith("/")) {
                return null;
            }
            absolute = workingDirectory + "/" + path;
        }

        Deque<String> elements = new ArrayDeque<>();
        for (String element : absolute.split("/")) {
            if (element.isEmpty() || ".".equals(element)) {
                // An empty element is a leading, repeated or trailing separator. UnixPath's
                // parser drops all three before normalize() ever runs, and normalize() drops
                // ".".
                continue;
            }
            if ("..".equals(element)) {
                // ".." at the root has nothing to remove and is dropped, matching the
                // isAbsolute() branch of UnixPath.normalize()'s name/".." pass: "/a/../.."
                // normalises to "/", not to "/..". Nothing here can leave a ".." in the deque,
                // because absolute always starts at the root.
                if (!elements.isEmpty()) {
                    elements.removeLast();
                }
                continue;
            }
            elements.addLast(element);
        }

        StringBuilder resolved = new StringBuilder();
        for (String element : elements) {
            resolved.append('/').append(element);
        }
        return resolved.length() == 0 ? "/" : resolved.toString();
    }

    /**
     * This JVM's filesystem encoding if it cannot represent the path, else null.
     *
     * <p>{@code Paths.get} encodes with {@code sun.jnu.encoding}, which follows the OS locale
     * and is ASCII on a container started with no {@code LANG} — the default for most base
     * images. A path holding any non-ASCII character then fails with "Malformed input or input
     * contains unmappable characters" and, because the same encoding is used for stdout, prints
     * as question marks. Nothing in that tells the reader it is a locale problem.
     *
     * <p>Answering null when the property is missing or unknown is deliberate: the caller then
     * refuses the URL instead of hand-rolling a path on a platform whose rules it has not
     * confirmed.
     */
    private static String encodingThatCannotHold(String pathPart) {
        try {
            // Inside the guard, not before it. Callers reach this from a catch block that may
            // be on its way to throwing a SQLException, and a security manager denying the
            // property read would replace that with an unchecked SecurityException -- losing
            // the real parse failure to a line that only exists to classify it.
            String encoding = System.getProperty("sun.jnu.encoding");
            if (encoding == null || Charset.forName(encoding).newEncoder().canEncode(pathPart)) {
                return null;
            }
            return encoding;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Explains the one path failure whose real cause is nowhere in the exception: a JVM whose
     * filesystem encoding cannot represent the name.
     *
     * <p>Only reachable now when the textual fallback declined the path as well — a filesystem
     * whose separator is not {@code /}, or a name holding a NUL. It is also not a chDB
     * limitation: the driver hands the engine UTF-8 bytes and the engine creates the directory
     * correctly; the same URL works in the same container with {@code LANG=C.UTF-8}.
     */
    private static String localeHint(String pathPart) {
        String encoding = encodingThatCannotHold(pathPart);
        if (encoding == null) {
            return "";
        }
        return ". This JVM's filesystem encoding (sun.jnu.encoding=" + encoding + ") cannot"
                + " represent that path, which is the JVM's reading of the OS locale rather than"
                + " a chDB limitation -- the engine itself stores paths as UTF-8 bytes and"
                + " handles this name. Start the JVM under a UTF-8 locale (LANG=C.UTF-8 or"
                + " LC_ALL=C.UTF-8; a container with no locale set defaults to ASCII), or use an"
                + " ASCII storage path";
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

    /**
     * Absolute normalized storage path, or null for {@code :memory:}.
     *
     * <p>Also null for a path this JVM's {@code sun.jnu.encoding} cannot encode, where no
     * {@link Path} can be constructed at all. The connection still works — see {@link
     * #registryKey()}, which is the form the driver compares and passes to the engine.
     */
    public Path storagePath() {
        return storagePath;
    }

    /**
     * The key {@link StoragePathRegistry} compares connections by: the absolute normalized
     * storage path, or {@code :memory:}. Never null.
     */
    public String registryKey() {
        return resolvedPath;
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
            arguments.add("--path=" + resolvedPath);
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
