package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChdbUrlTest {

    @Test
    @DisplayName("the bare prefix and :memory: both mean the in-memory database")
    void memoryForms() throws SQLException {
        for (String url : List.of("jdbc:chdb:", "jdbc:chdb::memory:")) {
            ChdbUrl parsed = ChdbUrl.parse(url, null);
            assertTrue(parsed.isMemory(), url);
            assertEquals(ChdbUrl.MEMORY, parsed.registryKey(), url);
        }
    }

    @Test
    @DisplayName(":memory: is not passed as --path, which the engine would take as a directory name")
    void memoryOmitsPathArgument() throws SQLException {
        List<String> arguments = ChdbUrl.parse("jdbc:chdb::memory:", null).toConnectArguments();
        assertEquals(List.of("clickhouse"), arguments);
    }

    @Test
    @DisplayName("a file path becomes an absolute --path")
    void filePath() throws SQLException {
        ChdbUrl parsed = ChdbUrl.parse("jdbc:chdb:/var/lib/chdb", null);
        assertFalse(parsed.isMemory());
        assertEquals(Paths.get("/var/lib/chdb"), parsed.storagePath());
        assertTrue(parsed.toConnectArguments().contains("--path=/var/lib/chdb"));
    }

    @Test
    @DisplayName("two spellings of one path compare equal, so they share the process binding")
    void normalizesPath() throws SQLException {
        ChdbUrl a = ChdbUrl.parse("jdbc:chdb:/var/lib/chdb", null);
        ChdbUrl b = ChdbUrl.parse("jdbc:chdb:/var/lib/./other/../chdb", null);
        assertEquals(a.registryKey(), b.registryKey());
    }

    @Test
    @DisplayName("a relative path is resolved against the working directory")
    void resolvesRelativePath() throws SQLException {
        ChdbUrl parsed = ChdbUrl.parse("jdbc:chdb:data/db", null);
        assertTrue(parsed.storagePath().isAbsolute());
        assertTrue(parsed.storagePath().endsWith(Paths.get("data/db")));
    }

    @Test
    @DisplayName("query properties are parsed and percent-decoded")
    void parsesQueryProperties() throws SQLException {
        ChdbUrl parsed = ChdbUrl.parse("jdbc:chdb:/db?max_threads=4&comment=a%20b", null);
        assertEquals("4", parsed.properties().get("max_threads"));
        assertEquals("a b", parsed.properties().get("comment"));
    }

    @Test
    @DisplayName("engine settings are forwarded as --key=value, driver properties are not")
    void forwardsEngineSettingsOnly() throws SQLException {
        ChdbUrl parsed =
                ChdbUrl.parse(
                        "jdbc:chdb:/db?max_threads=4&" + ChdbUrl.PROP_UNSUPPORTED_AS_BINARY + "=true",
                        null);
        List<String> arguments = parsed.toConnectArguments();
        assertTrue(arguments.contains("--max_threads=4"));
        assertFalse(
                arguments.stream().anyMatch(a -> a.contains(ChdbUrl.PROP_UNSUPPORTED_AS_BINARY)),
                "driver properties must not reach the engine: " + arguments);
        assertTrue(parsed.booleanProperty(ChdbUrl.PROP_UNSUPPORTED_AS_BINARY, false));
    }

    @Test
    @DisplayName("supplied Properties override the URL's query string")
    void suppliedPropertiesWin() throws SQLException {
        Properties supplied = new Properties();
        supplied.setProperty("max_threads", "8");
        ChdbUrl parsed = ChdbUrl.parse("jdbc:chdb:/db?max_threads=4", supplied);
        assertEquals("8", parsed.properties().get("max_threads"));
    }

    @Test
    @DisplayName("a non-chdb URL is refused, and accepts() says so first")
    void rejectsForeignUrl() {
        assertFalse(ChdbUrl.accepts("jdbc:postgresql://localhost/db"));
        assertFalse(ChdbUrl.accepts(null));
        SQLException e =
                assertThrows(
                        SQLException.class, () -> ChdbUrl.parse("jdbc:postgresql://localhost/db", null));
        assertEquals("08001", e.getSQLState());
    }

    @Test
    @DisplayName("a property without '=' is refused rather than silently dropped")
    void rejectsMalformedProperty() {
        SQLException e =
                assertThrows(SQLException.class, () -> ChdbUrl.parse("jdbc:chdb:/db?broken", null));
        assertTrue(e.getMessage().contains("Malformed property"), e.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // The textual absolutise-and-normalise that stands in for java.nio.file on a JVM whose
    // sun.jnu.encoding cannot hold the path (issue #7).
    //
    // These are the tests that make the fallback trustworthy, and they assert the semantics
    // that matter -- whether two URLs are judged to name the same directory -- rather than
    // merely that nothing throws. A subtly wrong normalisation would make the storage-path
    // registry refuse a second connection to the same directory, or admit one to a different
    // directory, both silently.
    // ---------------------------------------------------------------------------------------

    /** Paths whose textual resolution is interesting, in the order issue #7 lists them. */
    private static final List<String> AWKWARD_PATHS =
            List.of(
                    // "." and ".."
                    "/var/lib/.",
                    "/var/./lib",
                    "/var/lib/../lib",
                    "/var/lib/..",
                    // ".." at and past the root
                    "/..",
                    "/../..",
                    "/../../var",
                    "/var/../..",
                    "/var/lib/../../../var",
                    // repeated separators
                    "//var//lib",
                    "///var///lib///db",
                    "//",
                    "///",
                    // trailing separators
                    "/var/lib/",
                    "/var/lib//",
                    "/var/lib/./",
                    "/",
                    // relative
                    "data/db",
                    "./data/db",
                    "../data",
                    "../../data",
                    "data/../db",
                    "data/",
                    ".",
                    "..",
                    "./",
                    "../",
                    // empty segments and paths made only of dots
                    "/./.",
                    "/././/./",
                    ".//.",
                    "..//..",
                    // Windows-style input on a Unix JVM: "\" is an ordinary character in a
                    // name there, so this is one relative name element, not three.
                    "C:\\data\\db",
                    "\\\\server\\share\\db",
                    "data\\db",
                    // names that look like the in-memory URL but arrive as a path
                    ":memory:/db",
                    // the shapes the driver is actually asked for
                    "/var/lib/chdb",
                    "/tmp/chdb awkward/unicode-数据库-δεδομένα-🎉",
                    "/data/数据库/../数据库");

    @Test
    @DisplayName("the textual fallback agrees with java.nio.file on every path java.nio.file accepts")
    void textualFallbackMatchesPathsGet() {
        assumeTrue("/".equals(File.separator), "the fallback is only defined for a \"/\" filesystem");

        List<String> sample = new ArrayList<>(AWKWARD_PATHS);
        sample.addAll(randomPaths(1200, 20250909L, ASCII_ELEMENTS));
        // Non-ASCII draws add coverage where the platform can encode them and are skipped
        // where it cannot, which is why the floor below counts only the ASCII ones.
        sample.addAll(randomPaths(800, 987654321L, UNICODE_ELEMENTS));

        int compared = 0;
        for (String path : sample) {
            String expected;
            try {
                expected = Paths.get(path).toAbsolutePath().normalize().toString();
            } catch (RuntimeException e) {
                // This JVM cannot encode or parse it, so there is nothing to compare against.
                // Under an ASCII locale that is exactly the case the fallback exists for, and
                // the semantic tests below are what cover it.
                continue;
            }
            assertEquals(
                    expected,
                    ChdbUrl.resolveTextually(path),
                    "the textual fallback disagrees with java.nio.file for [" + path + "]");
            compared++;
        }
        assertTrue(compared >= 1200, "the differential sample was mostly skipped: " + compared);
    }

    private static final String[] ASCII_ELEMENTS = {
        "a", "bb", "c d", ".", "..", "", "x.y", ":memory:", "C:\\z", "...", ".hidden"
    };

    private static final String[] UNICODE_ELEMENTS = {
        "数据", "δεδομένα", "🎉", ".", "..", "", "a", "café"
    };

    /**
     * Paths assembled from the elements that drive normalisation, with separators repeated at
     * random. A fixed seed, so a failure is reproducible from the message alone.
     */
    private static List<String> randomPaths(int count, long seed, String[] elements) {
        String[] separators = {"/", "//", "///"};
        Random random = new Random(seed);
        List<String> paths = new ArrayList<>(count);
        while (paths.size() < count) {
            StringBuilder path = new StringBuilder();
            if (random.nextBoolean()) {
                path.append(separators[random.nextInt(separators.length)]);
            }
            int length = 1 + random.nextInt(6);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    path.append(separators[random.nextInt(separators.length)]);
                }
                path.append(elements[random.nextInt(elements.length)]);
            }
            if (random.nextBoolean()) {
                path.append(separators[random.nextInt(separators.length)]);
            }
            if (path.length() > 0) {
                // An all-empty draw yields "", which is the in-memory URL and never reaches
                // path resolution.
                paths.add(path.toString());
            }
        }
        return paths;
    }

    @Test
    @DisplayName("the textual fallback resolves to an absolute path with no \".\" or \"..\" left")
    void textualFallbackAbsolutisesAndNormalises() {
        assumeTrue("/".equals(File.separator));

        String cwd = System.getProperty("user.dir");
        assertEquals("/var/lib/chdb", ChdbUrl.resolveTextually("/var/lib/chdb"));
        assertEquals("/var/lib/chdb", ChdbUrl.resolveTextually("/var/lib/./other/../chdb"));
        assertEquals("/var/lib", ChdbUrl.resolveTextually("/var/lib/"));
        assertEquals("/var/lib", ChdbUrl.resolveTextually("//var///lib//"));
        assertEquals("/", ChdbUrl.resolveTextually("/"));
        assertEquals("/", ChdbUrl.resolveTextually("///"));
        assertEquals("/", ChdbUrl.resolveTextually("/./."));
        // ".." past the root stops at the root rather than escaping it or leaving a "..".
        assertEquals("/", ChdbUrl.resolveTextually("/.."));
        assertEquals("/", ChdbUrl.resolveTextually("/../.."));
        assertEquals("/var", ChdbUrl.resolveTextually("/../../var"));
        assertEquals(cwd + "/data/db", ChdbUrl.resolveTextually("data/db"));
        assertEquals(cwd + "/data/db", ChdbUrl.resolveTextually("./data//db/"));
        assertEquals(cwd, ChdbUrl.resolveTextually("."));
        assertEquals(cwd, ChdbUrl.resolveTextually("./"));
        // A name containing backslashes is one element on a Unix filesystem.
        assertEquals(cwd + "/C:\\data\\db", ChdbUrl.resolveTextually("C:\\data\\db"));
        for (String resolved : List.of(ChdbUrl.resolveTextually(".."), ChdbUrl.resolveTextually("../.."))) {
            assertTrue(resolved.startsWith("/"), resolved);
            assertFalse(resolved.contains("/.."), resolved);
            assertFalse(resolved.endsWith("/") && resolved.length() > 1, resolved);
        }
    }

    @Test
    @DisplayName("the fallback judges two spellings of one directory the same, and two directories different")
    void textualFallbackDecidesSameDirectory() {
        assumeTrue("/".equals(File.separator));

        // Same directory, written every awkward way issue #7 names.
        List<String> sameDirectory =
                List.of(
                        "/data/数据库",
                        "/data/数据库/",
                        "/data/数据库//",
                        "//data//数据库",
                        "/data/./数据库",
                        "/data/other/../数据库",
                        "/data/数据库/./",
                        "/../data/数据库",
                        "/data/数据库/../数据库");
        String key = ChdbUrl.resolveTextually("/data/数据库");
        assertEquals("/data/数据库", key);
        for (String spelling : sameDirectory) {
            assertEquals(
                    key,
                    ChdbUrl.resolveTextually(spelling),
                    "[" + spelling + "] should be judged the same directory as " + key);
        }

        // Different directories must stay different -- the failure that would wrongly let a
        // second connection bind a directory the first one is not using.
        List<String> otherDirectories =
                List.of(
                        "/data/数据庫",
                        "/data/数据库2",
                        "/data/数据库/sub",
                        "/data",
                        "/other/数据库",
                        "/data/数据库x/..",
                        "data/数据库");
        for (String other : otherDirectories) {
            assertFalse(
                    key.equals(ChdbUrl.resolveTextually(other)),
                    "[" + other + "] should not be judged the same directory as " + key);
        }
    }

    @Test
    @DisplayName("a non-ASCII path parses, and its registry key is the resolved path either way")
    void nonAsciiPathParses() throws SQLException {
        assumeTrue("/".equals(File.separator));

        // On a UTF-8 JVM this goes through java.nio.file; on an ASCII-locale JVM it goes
        // through the fallback. The point of the change is that the answer is the same, so this
        // test asserts the answer rather than which route produced it.
        ChdbUrl parsed = ChdbUrl.parse("jdbc:chdb:/data/数据库/../数据库", null);
        assertFalse(parsed.isMemory());
        assertEquals("/data/数据库", parsed.registryKey());
        assertTrue(parsed.toConnectArguments().contains("--path=/data/数据库"));

        // And the two spellings share the process binding, which is the whole reason the key
        // is computed at all.
        assertEquals(parsed.registryKey(), ChdbUrl.parse("jdbc:chdb://data//数据库/", null).registryKey());
    }

    @Test
    @DisplayName("a path holding a NUL is still refused: it would truncate the engine's C string")
    void refusesNulInPath() {
        assumeTrue("/".equals(File.separator));

        assertNull(ChdbUrl.resolveTextually("/data/db\u0000/x"));
        SQLException e =
                assertThrows(
                        SQLException.class,
                        () -> ChdbUrl.parse("jdbc:chdb:/data/db\u0000/x", null));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("Cannot resolve the storage path"), e.getMessage());
    }

    @Test
    @DisplayName("the in-memory URLs never go through path resolution at all")
    void memoryUrlsSkipPathResolution() throws SQLException {
        for (String url : List.of("jdbc:chdb:", "jdbc:chdb::memory:", "jdbc:chdb: :memory: ",
                "jdbc:chdb::memory:?max_threads=4")) {
            ChdbUrl parsed = ChdbUrl.parse(url, null);
            assertTrue(parsed.isMemory(), url);
            assertEquals(ChdbUrl.MEMORY, parsed.rawPath(), url);
            assertEquals(ChdbUrl.MEMORY, parsed.registryKey(), url);
            assertNull(parsed.storagePath(), url);
            assertFalse(
                    parsed.toConnectArguments().stream().anyMatch(a -> a.startsWith("--path=")),
                    url);
        }
        // ":memory:" as part of a longer path is a directory name, not the in-memory database.
        ChdbUrl notMemory = ChdbUrl.parse("jdbc:chdb:/data/:memory:", null);
        assertFalse(notMemory.isMemory());
        assertEquals("/data/:memory:", notMemory.registryKey());
        assertNotNull(ChdbUrl.resolveTextually("/data/:memory:"));
    }
}
