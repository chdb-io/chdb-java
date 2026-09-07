package org.chdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
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
}
