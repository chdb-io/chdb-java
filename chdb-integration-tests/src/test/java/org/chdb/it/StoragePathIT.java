package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine binds one storage path per process (work plan section 5.7). These tests pin the
 * three behaviours that follow, because each one is something a user will hit: many connections
 * to one path work, a second path is refused with a usable message, and the binding is released
 * when the last connection closes.
 */
class StoragePathIT extends NativeTestBase {

    @TempDir Path temp;

    @Test
    @DisplayName("several connections share one storage path and see each other's writes")
    void manyConnectionsOnePath() throws SQLException {
        String url = "jdbc:chdb:" + temp.resolve("shared");
        try (Connection first = DriverManager.getConnection(url);
                Connection second = DriverManager.getConnection(url);
                Connection third = DriverManager.getConnection(url)) {

            try (Statement statement = first.createStatement()) {
                statement.execute("CREATE TABLE shared (x UInt32) ENGINE = MergeTree ORDER BY x");
                assertEquals(1, statement.executeUpdate("INSERT INTO shared VALUES (7)"));
            }

            // The second connection is a separate engine session over the same storage, so it
            // must see the committed write.
            try (Statement statement = second.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT x FROM shared")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
            try (Statement statement = third.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT count() FROM shared")) {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong(1));
            }
        }
    }

    @Test
    @DisplayName("a second storage path is refused with the bound path, the requested one and a fix")
    void secondPathIsRefused() throws SQLException {
        String bound = "jdbc:chdb:" + temp.resolve("bound");
        String other = "jdbc:chdb:" + temp.resolve("other");

        try (Connection connection = DriverManager.getConnection(bound)) {
            SQLException e =
                    assertThrows(SQLException.class, () -> DriverManager.getConnection(other));

            assertEquals("08004", e.getSQLState());
            String message = e.getMessage();
            // The message has to answer "why" and "what do I do", because the engine's own
            // answer is a null return from chdb_connect.
            assertTrue(message.contains("currently bound"), message);
            assertTrue(message.contains(temp.resolve("bound").toString()), message);
            assertTrue(message.contains(temp.resolve("other").toString()), message);
            assertTrue(message.contains("open connections"), message);
            assertTrue(message.contains(bound), "the holding URL should be named: " + message);
            assertTrue(message.contains("Close every open Connection"), message);

            // And the refusal left the working connection alone.
            assertTrue(connection.isValid(1));
        }
    }

    @Test
    @DisplayName("in-memory counts as a bound path, and the message says so")
    void memoryConflictsWithAFilePath() throws SQLException {
        try (Connection memory = openMemory()) {
            SQLException e =
                    assertThrows(
                            SQLException.class,
                            () -> DriverManager.getConnection("jdbc:chdb:" + temp.resolve("onDisk")));
            assertTrue(e.getMessage().contains(":memory:"), e.getMessage());
            assertTrue(
                    e.getMessage().contains("easy to open by accident"),
                    "the message should explain that a pathless URL means :memory:: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("once the last connection closes, a different path binds")
    void rebindAfterLastClose() throws SQLException {
        String first = "jdbc:chdb:" + temp.resolve("first");
        String second = "jdbc:chdb:" + temp.resolve("second");

        try (Connection connection = DriverManager.getConnection(first);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t1 (x UInt8) ENGINE = Memory");
        }

        try (Connection connection = DriverManager.getConnection(second)) {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
            // Sequentially, not nested: a Connection runs one statement at a time, so the
            // result set above has to be closed before this one opens.
            try (Statement statement = connection.createStatement();
                    ResultSet exists =
                            statement.executeQuery(
                                    "SELECT count() FROM system.tables"
                                            + " WHERE database = 'default' AND name = 't1'")) {
                assertTrue(exists.next());
                // A fresh storage path: the first path's table must not be visible.
                assertEquals(0L, exists.getLong(1), "the new path should not see the old path's table");
            }
        }
    }

    @Test
    @DisplayName("a failed connect does not leave the path pinned")
    void failedConnectReleasesTheBinding() throws SQLException, IOException {
        // A storage path that names an existing regular file cannot be opened, which is a
        // failure the driver reaches only after it has reserved the path. If the reservation
        // were not released, every later connect to a different path would be refused on behalf
        // of a connection that does not exist -- and one bad connect would strand the JVM.
        //
        // Not an invalid setting value: engine 26.7.0 accepts --max_threads=not-a-number and
        // connects anyway, despite what chdb.h says about invalid values failing the
        // connection, so that would not exercise this path.
        Path file = temp.resolve("a-file-not-a-directory");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:chdb:" + file));

        try (Connection connection = DriverManager.getConnection("jdbc:chdb:" + temp.resolve("after"));
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }
    }

    @Test
    @DisplayName("two spellings of one path are the same binding")
    void equivalentPathsShareTheBinding() throws SQLException, IOException {
        Path directory = temp.resolve("canonical");
        Files.createDirectories(directory);
        String direct = "jdbc:chdb:" + directory;
        String roundabout = "jdbc:chdb:" + temp.resolve("elsewhere").resolve("..").resolve("canonical");

        try (Connection first = DriverManager.getConnection(direct);
                Connection second = DriverManager.getConnection(roundabout)) {
            // If normalization did not happen these would be two paths and the second would be
            // refused.
            assertTrue(second.isValid(1));
        }
    }

    @Test
    @DisplayName("on-disk data survives closing and reopening the connection")
    void dataPersists() throws SQLException {
        String url = "jdbc:chdb:" + temp.resolve("persistent");
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE kept (x UInt32) ENGINE = MergeTree ORDER BY x");
            statement.executeUpdate("INSERT INTO kept VALUES (1), (2), (3)");
        }
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT sum(x) FROM kept")) {
            assertTrue(rs.next());
            assertEquals(6L, rs.getLong(1));
        }
    }

    @Test
    @DisplayName("in-memory data does not outlive the last connection")
    void memoryDoesNotPersist() throws SQLException {
        // The regression this guards: passing --path=:memory: makes the engine create a
        // directory literally named ":memory:" in the working directory, so the "in-memory"
        // database silently becomes an on-disk one that outlives the JVM.
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ephemeral (x UInt8) ENGINE = Memory");
        }
        assertTrue(
                !Files.exists(Path.of(":memory:")),
                "a directory named ':memory:' was created in the working directory, which means"
                        + " --path=:memory: was passed to the engine");
    }
}
