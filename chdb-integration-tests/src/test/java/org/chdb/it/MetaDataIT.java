package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metadata a JDBC framework reads before it does anything else (work plan section 5.10),
 * plus the JDBC surface V1 refuses.
 *
 * <p>The refusals matter as much as the answers: a framework that asks whether transactions are
 * supported and is told "yes" will build a unit of work that silently is not one.
 */
class MetaDataIT extends NativeTestBase {

    @Test
    @DisplayName("the product identifies itself and its versions")
    void identity() throws SQLException {
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertEquals("chDB", meta.getDatabaseProductName());
            assertTrue(meta.getDatabaseProductVersion().matches("\\d+\\.\\d+.*"),
                    meta.getDatabaseProductVersion());
            assertTrue(meta.getDatabaseMajorVersion() > 0);
            assertEquals("chDB JDBC Driver", meta.getDriverName());
            assertEquals(4, meta.getJDBCMajorVersion());
            assertEquals(connection.getMetaData().getURL(), MEMORY_URL);
            assertEquals("`", meta.getIdentifierQuoteString());
            assertEquals("database", meta.getSchemaTerm());
        }
    }

    @Test
    @DisplayName("transactions are reported as absent, consistently")
    void transactionsAreAbsent() throws SQLException {
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertFalse(meta.supportsTransactions());
            assertEquals(Connection.TRANSACTION_NONE, meta.getDefaultTransactionIsolation());
            assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_NONE));
            assertFalse(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
            assertFalse(meta.supportsSavepoints());
            assertFalse(meta.supportsMultipleTransactions());
            assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());

            // And the Connection agrees, rather than metadata and behaviour disagreeing.
            assertTrue(connection.getAutoCommit());
            assertThrows(SQLFeatureNotSupportedException.class, () -> connection.setAutoCommit(false));
            assertThrows(SQLFeatureNotSupportedException.class, connection::commit);
            assertThrows(SQLFeatureNotSupportedException.class, connection::rollback);
            assertThrows(SQLFeatureNotSupportedException.class, connection::setSavepoint);
            // Setting auto-commit to its actual value is not an error.
            connection.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("result sets are reported as forward-only and read-only, consistently")
    void resultSetCapabilities() throws SQLException {
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
            assertFalse(meta.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
            assertTrue(
                    meta.supportsResultSetConcurrency(
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            assertFalse(
                    meta.supportsResultSetConcurrency(
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
            assertFalse(meta.supportsBatchUpdates());
            assertFalse(meta.supportsGetGeneratedKeys());
            assertFalse(meta.supportsStoredProcedures());

            // Asking for what is not supported is refused at creation, not silently downgraded.
            assertThrows(
                    SQLFeatureNotSupportedException.class,
                    () ->
                            connection.createStatement(
                                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
            assertThrows(
                    SQLFeatureNotSupportedException.class,
                    () ->
                            connection.createStatement(
                                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        }
    }

    @Test
    @DisplayName("getSchemas lists the engine's databases")
    void schemas() throws SQLException {
        try (Connection connection = openMemory();
                ResultSet rs = connection.getMetaData().getSchemas()) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TABLE_SCHEM"));
            }
            assertTrue(names.contains("default"), names.toString());
            assertTrue(names.contains("system"), names.toString());
        }
    }

    @Test
    @DisplayName("getCatalogs is empty, because ClickHouse has one namespace level and it is the schema")
    void catalogsAreEmpty() throws SQLException {
        try (Connection connection = openMemory();
                ResultSet rs = connection.getMetaData().getCatalogs()) {
            assertFalse(rs.next());
            assertEquals("TABLE_CAT", rs.getMetaData().getColumnName(1));
        }
    }

    @Test
    @DisplayName("getTables and getColumns report a table the test just created")
    void tablesAndColumns() throws SQLException {
        try (Connection connection = openMemory()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS meta_probe (id UInt32, name Nullable(String),"
                                + " created DateTime64(3)) ENGINE = Memory");
            }

            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(null, "default", "meta_probe", null)) {
                assertTrue(rs.next());
                assertEquals("default", rs.getString("TABLE_SCHEM"));
                assertEquals("meta_probe", rs.getString("TABLE_NAME"));
                assertEquals("TABLE", rs.getString("TABLE_TYPE"));
                assertFalse(rs.next());
            }

            try (ResultSet rs = meta.getColumns(null, "default", "meta_probe", null)) {
                List<String> columns = new ArrayList<>();
                List<String> types = new ArrayList<>();
                List<Integer> nullability = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                    types.add(rs.getString("TYPE_NAME"));
                    nullability.add(rs.getInt("NULLABLE"));
                }
                assertEquals(List.of("id", "name", "created"), columns);
                assertEquals(List.of("UInt32", "Nullable(String)", "DateTime64(3)"), types);
                assertEquals(
                        List.of(
                                DatabaseMetaData.columnNoNulls,
                                DatabaseMetaData.columnNullable,
                                DatabaseMetaData.columnNoNulls),
                        nullability);
            }

            // The pattern is a LIKE pattern, and a non-matching one must return nothing rather
            // than everything.
            try (ResultSet rs = meta.getTables(null, "default", "no_such_table_%", null)) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getTables(null, "default", "meta_pro%", null)) {
                assertTrue(rs.next());
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE meta_probe");
            }
        }
    }

    @Test
    @DisplayName("the JDBC type of a column comes from a zero-row query, which is the one mapping")
    void columnTypesViaEmptyQuery() throws SQLException {
        try (Connection connection = openMemory()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS type_probe (id UInt32, name String)"
                                + " ENGINE = Memory");
            }
            // getColumns reports the ClickHouse type name and leaves DATA_TYPE as OTHER on
            // purpose: mapping a type *name* to a JDBC type would be a second implementation of
            // the Arrow mapping, and the two would drift. This is the documented way to get it.
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT * FROM type_probe LIMIT 0")) {
                assertEquals(2, rs.getMetaData().getColumnCount());
                assertEquals(java.sql.Types.BIGINT, rs.getMetaData().getColumnType(1));
                assertEquals(java.sql.Types.VARCHAR, rs.getMetaData().getColumnType(2));
                assertFalse(rs.next());
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE type_probe");
            }
        }
    }

    @Test
    @DisplayName("getTableTypes and getTypeInfo answer without error")
    void tableTypesAndTypeInfo() throws SQLException {
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTableTypes()) {
                List<String> types = new ArrayList<>();
                while (rs.next()) {
                    types.add(rs.getString("TABLE_TYPE"));
                }
                assertTrue(types.contains("TABLE"), types.toString());
                assertTrue(types.contains("VIEW"), types.toString());
            }
            try (ResultSet rs = meta.getTypeInfo()) {
                int count = 0;
                while (rs.next()) {
                    assertTrue(rs.getString("TYPE_NAME") != null);
                    count++;
                }
                assertTrue(count > 10, "expected the engine's type families, got " + count);
            }
        }
    }

    @Test
    @DisplayName("the constraint and privilege queries return well-formed empty result sets")
    void emptyCatalogQueries() throws SQLException {
        // ClickHouse has none of these. Returning an empty result set with the columns JDBC
        // specifies lets a framework's introspection complete; throwing would abort it.
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getPrimaryKeys(null, "default", "anything")) {
                assertEquals("PK_NAME", rs.getMetaData().getColumnName(6));
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getImportedKeys(null, "default", "anything")) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getExportedKeys(null, "default", "anything")) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getIndexInfo(null, "default", "anything", false, true)) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getProcedures(null, "default", "%")) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getTablePrivileges(null, "default", "%")) {
                assertFalse(rs.next());
            }
            try (ResultSet rs = meta.getUDTs(null, "default", "%", null)) {
                assertFalse(rs.next());
            }
        }
    }

    @Test
    @DisplayName("getFunctions lists the engine's functions")
    void functions() throws SQLException {
        try (Connection connection = openMemory();
                ResultSet rs = connection.getMetaData().getFunctions(null, null, "toDateTime64")) {
            assertTrue(rs.next());
            assertEquals("toDateTime64", rs.getString("FUNCTION_NAME"));
        }
    }

    @Test
    @DisplayName("switching the current database through setSchema works and is reported back")
    void schemaSwitching() throws SQLException {
        try (Connection connection = openMemory()) {
            assertEquals("default", connection.getSchema());
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS other_db");
            }
            connection.setSchema("other_db");
            assertEquals("other_db", connection.getSchema());
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT currentDatabase()")) {
                assertTrue(rs.next());
                assertEquals("other_db", rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("unimplemented JDBC methods throw rather than returning a fake success")
    void unsupportedSurfaceThrows() throws SQLException {
        try (Connection connection = openMemory()) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> connection.prepareCall("x"));
            assertThrows(SQLFeatureNotSupportedException.class, connection::createBlob);
            assertThrows(SQLFeatureNotSupportedException.class, connection::createClob);
            assertThrows(SQLFeatureNotSupportedException.class, connection::createSQLXML);
            assertThrows(
                    SQLFeatureNotSupportedException.class,
                    () -> connection.createArrayOf("Int32", new Object[0]));

            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLFeatureNotSupportedException.class, () -> statement.addBatch("SELECT 1"));
                assertThrows(SQLFeatureNotSupportedException.class, statement::getGeneratedKeys);
                assertThrows(
                        SQLFeatureNotSupportedException.class, () -> statement.setCursorName("c"));

                try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertThrows(SQLFeatureNotSupportedException.class, rs::previous);
                    assertThrows(SQLFeatureNotSupportedException.class, rs::first);
                    assertThrows(SQLFeatureNotSupportedException.class, rs::last);
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rs.absolute(1));
                    assertThrows(SQLFeatureNotSupportedException.class, rs::isLast);
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rs.updateInt(1, 1));
                    assertThrows(SQLFeatureNotSupportedException.class, rs::insertRow);
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getArray(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getBlob(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getRowId(1));
                }
            }
        }
    }

    @Test
    @DisplayName("unwrap reaches the driver's own types and refuses anything else")
    void unwrapping() throws SQLException {
        try (Connection connection = openMemory()) {
            assertTrue(connection.isWrapperFor(Connection.class));
            assertEquals(connection, connection.unwrap(Connection.class));
            assertFalse(connection.isWrapperFor(java.sql.Statement.class));
            assertThrows(SQLException.class, () -> connection.unwrap(java.sql.Statement.class));
        }
    }
}
