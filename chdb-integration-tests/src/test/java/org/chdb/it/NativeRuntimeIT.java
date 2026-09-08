package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.chdb.internal.ChdbNative;
import org.chdb.internal.NativeLibraryLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M1: the native runtime loads, identifies itself, and answers SELECT 1. */
class NativeRuntimeIT extends NativeTestBase {

    @Test
    @DisplayName("the driver is discovered through DriverManager without Class.forName")
    void driverIsAutoDiscovered() throws SQLException {
        // The service file in META-INF/services/java.sql.Driver is what makes this work; a
        // shading step that drops it would break every consumer, so it is worth asserting.
        Driver driver = DriverManager.getDriver(MEMORY_URL);
        assertEquals("org.chdb.jdbc.ChdbDriver", driver.getClass().getName());
        assertTrue(driver.acceptsURL(MEMORY_URL));
        assertTrue(driver.acceptsURL("jdbc:chdb:/tmp/whatever"));
        assertEquals(null, driver.connect("jdbc:postgresql://localhost/x", null),
                "a foreign URL must be declined with null so DriverManager can try the next driver");
    }

    @Test
    @DisplayName("the loaded engine is the pinned one, and the shim's ABI matches the driver's")
    void versionsAgree() throws SQLException {
        try (Connection connection = openMemory()) {
            NativeLibraryLoader.LoadedRuntime runtime = NativeLibraryLoader.loadedRuntime();
            assertNotNull(runtime.platform());

            String expectedEngine = System.getProperty("chdb.it.engine.version");
            if (expectedEngine != null && !expectedEngine.isEmpty()) {
                assertEquals(expectedEngine, runtime.engineVersion(),
                        "chdb_version() must equal the engine pinned in scripts/engine.properties");
            }
            assertEquals(ChdbNative.JNI_ABI_VERSION, ChdbNative.jniAbiVersion());
            assertEquals(runtime.engineVersion(), connection.getMetaData().getDatabaseProductVersion());

            String buildInfo = ChdbNative.shimBuildInfo();
            assertTrue(buildInfo.contains("shim.commit="), buildInfo);
            assertTrue(buildInfo.contains("shim.expected.engine.version="), buildInfo);
        }
    }

    @Test
    @DisplayName("SELECT 1 round-trips with correct metadata")
    void selectOne() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1 AS one")) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(1, meta.getColumnCount());
            assertEquals("one", meta.getColumnName(1));
            assertEquals("one", meta.getColumnLabel(1));
            // ClickHouse types 1 as UInt8, which widens to a Java short.
            assertEquals("UInt8", meta.getColumnTypeName(1));
            assertEquals(Types.SMALLINT, meta.getColumnType(1));
            assertEquals(ResultSetMetaData.columnNoNulls, meta.isNullable(1));

            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1, rs.getInt("one"));
            assertEquals("1", rs.getString(1));
            assertTrue(!rs.next(), "SELECT 1 must yield exactly one row");
        }
    }

    @Test
    @DisplayName("isValid() makes a real round trip, and reports false once closed")
    void isValid() throws SQLException {
        Connection connection = openMemory();
        assertTrue(connection.isValid(1));
        connection.close();
        assertTrue(!connection.isValid(1));
    }

    @Test
    @DisplayName("column indexes are 1-based and out-of-range access is refused")
    void columnIndexBounds() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1 AS a, 2 AS b")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
            assertEquals(2, rs.getInt(2));
            for (int bad : new int[] {0, -1, 3, 99}) {
                SQLException e =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                SQLException.class, () -> rs.getInt(bad), "index " + bad);
                assertTrue(e.getMessage().contains("out of range"), e.getMessage());
            }
            SQLException e =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            SQLException.class, () -> rs.getInt("nope"));
            assertTrue(e.getMessage().contains("No column named"), e.getMessage());
        }
    }

    @Test
    @DisplayName("reading before next() is refused rather than returning a wrong value")
    void readBeforeFirstRow() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            SQLException e =
                    org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> rs.getInt(1));
            assertTrue(e.getMessage().contains("No current row"), e.getMessage());
        }
    }

    @Test
    @DisplayName("an empty result set has metadata but no rows")
    void emptyResultSet() throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1 AS a WHERE 0")) {
            assertEquals(1, rs.getMetaData().getColumnCount());
            assertEquals("a", rs.getMetaData().getColumnName(1));
            assertTrue(!rs.next());
        }
    }
}
