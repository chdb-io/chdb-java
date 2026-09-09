package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole {@link DatabaseMetaData} surface, called reflectively (issue #11).
 *
 * <p>This stands in for a JDBC GUI. DBeaver, DataGrip and SQuirreL all sweep most of this
 * interface while a connection is being opened, before the user has typed a query, and they do
 * it whether or not the answer is useful to them. The failure mode that matters is not a wrong
 * answer -- it is one method throwing {@link SQLFeatureNotSupportedException} from inside that
 * sweep, because the tool reports it as "could not connect" and the driver looks broken on
 * first contact. Installing DBeaver in CI is not practical, so the sweep is reproduced here:
 * every method the running JDK reports on the interface is invoked with arguments a tool would
 * plausibly pass, and the outcome is classified. That is 179 methods on JDK 11, 21 and 26 alike
 * -- the size of the surface is the JDK's business, so the test asserts it reached all of them
 * rather than asserting the number.
 *
 * <p>The second thing this covers is the empty result set. Roughly half of the catalog queries
 * have no rows to return in this driver -- ClickHouse has no foreign keys, no indexes in the
 * JDBC sense, no procedures and no UDTs -- and an empty {@link ResultSet} with correct metadata
 * is a different code path from a populated one. jOOQ and MyBatis read the column *labels* off
 * these result sets, so a zero-row set carrying the wrong column names is silently as broken as
 * a thrown exception, and much harder to notice. {@link #everyResultSetCarriesItsJdbcColumns()}
 * checks the labels against the names JDBC specifies.
 *
 * <p>The scan prints its table to stdout; {@code docs/unsupported.md} carries the summary that
 * came out of it.
 */
class DatabaseMetaDataSurfaceIT extends NativeTestBase {

    /** A database and table for the scan to find, so the table-scoped calls have real input. */
    private static final String SCHEMA = "meta_surface_scan";

    private static final String TABLE = "scan_target";

    /** How a scanned call turned out. The four buckets issue #11 asks the scan to report. */
    private enum Outcome {
        /** Returned a value, or a result set with at least one row. */
        VALUE,
        /** Returned a result set with no rows. Correct metadata still required. */
        EMPTY_RESULT_SET,
        /** Threw {@link SQLFeatureNotSupportedException} -- the outcome a GUI cannot absorb. */
        UNSUPPORTED,
        /** Threw something else. */
        ERROR
    }

    private static final class Result {
        final Outcome outcome;
        final String detail;

        Result(Outcome outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    @Test
    @DisplayName("no DatabaseMetaData method throws when a GUI sweeps the whole interface")
    void wholeSurfaceIsAnswerable() throws Exception {
        Map<String, Result> results = new TreeMap<>();
        try (Connection connection = openMemory()) {
            createScanTarget(connection);
            DatabaseMetaData meta = connection.getMetaData();
            for (Method method : scannableMethods()) {
                results.put(signature(method), invoke(meta, method));
            }
            dropScanTarget(connection);
        }

        System.out.println(report(results));

        // The finding the issue is after: anything a GUI calls unconditionally that refuses to
        // answer. There should be none -- DatabaseMetaData is the one interface in this driver
        // with no SQLFeatureNotSupportedException in it, and this test is what keeps it so.
        List<String> unsupported = namesWithOutcome(results, Outcome.UNSUPPORTED);
        assertTrue(
                unsupported.isEmpty(),
                "DatabaseMetaData methods throwing SQLFeatureNotSupportedException: " + unsupported);

        List<String> errors = namesWithOutcome(results, Outcome.ERROR);
        assertTrue(errors.isEmpty(), "DatabaseMetaData methods throwing: " + errorDetail(results));

        // Coverage, not a count.
        //
        // How many methods DatabaseMetaData has is a property of the JDK, not of this driver:
        // measured at 179 on JDK 11, 21 and 26 alike (177 declared on the interface, of which
        // getSchemas and supportsConvert are overload pairs, plus unwrap and isWrapperFor from
        // Wrapper). Asserting 179 would make the test a hostage to the next JDBC revision, and
        // asserting a floor -- which this used to do -- lets a method be skipped without anyone
        // noticing, which is the one failure the assertion is here to catch.
        //
        // So it asserts the property the test actually means: every method the running JDK
        // reports got invoked and classified, exactly once. That holds on any JDK, and fails if
        // the scan silently stops reaching part of the interface or if two methods collapse
        // into one signature.
        assertEquals(
                DatabaseMetaData.class.getMethods().length,
                results.size(),
                "every method on DatabaseMetaData must be scanned exactly once");
    }

    @Test
    @DisplayName("every metadata result set carries the column labels JDBC specifies, rows or not")
    void everyResultSetCarriesItsJdbcColumns() throws Exception {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        List<String> unspecified = new ArrayList<>();
        int checked = 0;
        try (Connection connection = openMemory()) {
            createScanTarget(connection);
            DatabaseMetaData meta = connection.getMetaData();
            for (Method method : scannableMethods()) {
                if (!ResultSet.class.equals(method.getReturnType())) {
                    continue;
                }
                List<String> expected = JDBC_COLUMNS.get(method.getName());
                if (expected == null) {
                    // A catalog query with no transcribed column list would otherwise be
                    // skipped in silence, so the gap is collected and failed on below rather
                    // than shrinking the test's reach without saying so.
                    unspecified.add(method.getName());
                    continue;
                }
                checked++;
                try (ResultSet rs = (ResultSet) method.invoke(meta, argumentsFor(method))) {
                    List<String> actual = labels(rs.getMetaData());
                    // JDBC lets a driver append columns beyond the specified ones, but the
                    // specified ones must be present, spelled as the spec spells them, because
                    // that is how jOOQ and MyBatis address them. Order is not asserted:
                    // findColumn() is by name.
                    List<String> absent = new ArrayList<>();
                    for (String column : expected) {
                        if (!actual.contains(column)) {
                            absent.add(column);
                        }
                    }
                    if (!absent.isEmpty()) {
                        missing.put(method.getName(), absent);
                    }

                    // And the per-column metadata has to answer on a zero-row set too: jOOQ
                    // reads the type of every column before it reads any row, so a driver that
                    // only populates ResultSetMetaData once a batch has arrived fails here and
                    // nowhere else.
                    probeColumnMetadata(method.getName(), rs.getMetaData());
                }
            }
            dropScanTarget(connection);
        }
        assertTrue(missing.isEmpty(), "metadata result sets missing JDBC columns: " + missing);
        assertTrue(
                unspecified.isEmpty(),
                "ResultSet-returning methods with no transcribed column list: " + unspecified);
        // Every ResultSet-returning method on the interface, and the count is derived from the
        // interface rather than written down, for the reason in wholeSurfaceIsAnswerable().
        assertEquals(resultSetMethodCount(), checked, "not every catalog query was checked");
    }

    /** How many methods on {@link DatabaseMetaData} return a {@link ResultSet}. */
    private static int resultSetMethodCount() {
        int count = 0;
        for (Method method : DatabaseMetaData.class.getMethods()) {
            if (ResultSet.class.equals(method.getReturnType())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("a zero-row metadata result set is readable rather than merely non-null")
    void emptyResultSetsBehaveLikeResultSets() throws Exception {
        try (Connection connection = openMemory()) {
            DatabaseMetaData meta = connection.getMetaData();
            // getImportedKeys on a table that does not exist: no rows either way, since
            // ClickHouse has no foreign keys. The point is what a caller can do with the set.
            try (ResultSet rs = meta.getImportedKeys(null, SCHEMA, "no_such_table")) {
                ResultSetMetaData rsmd = rs.getMetaData();
                assertEquals(14, rsmd.getColumnCount(), "getImportedKeys has 14 JDBC columns");
                assertFalse(rs.next(), "no rows");
                // next() returning false twice, rather than throwing the second time, is what a
                // framework's while(rs.next()) loop plus a defensive re-check relies on.
                assertFalse(rs.next());
                // findColumn works on an exhausted empty set; getters after the last row do not,
                // and JDBC does not require them to.
                assertEquals(1, rs.findColumn("PKTABLE_CAT"));
            }
        }
    }

    // ---------------------------------------------------------------- the scan

    /** Creates the database and table the table-scoped metadata calls will be pointed at. */
    private static void createScanTarget(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + "." + TABLE);
            statement.execute(
                    "CREATE TABLE " + SCHEMA + "." + TABLE
                            + " (id UInt64, name Nullable(String), at DateTime) ENGINE = Memory");
        }
    }

    /** Removes the scan target, so nothing later in the run sees a database it did not make. */
    private static void dropScanTarget(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    /**
     * Every method the running JDK reports on {@link DatabaseMetaData}, in signature order.
     *
     * <p>No filtering: {@code getMethods()} on an interface does not report {@code Object}'s
     * methods, checked at 0 on JDK 11, 21 and 26. Taking the array as it comes is what lets
     * {@link #wholeSurfaceIsAnswerable()} assert an exact one-result-per-method equality rather
     * than a threshold.
     */
    private static List<Method> scannableMethods() {
        List<Method> methods = new ArrayList<>(Arrays.asList(DatabaseMetaData.class.getMethods()));
        methods.sort(Comparator.comparing(DatabaseMetaDataSurfaceIT::signature));
        return methods;
    }

    private Result invoke(DatabaseMetaData meta, Method method) {
        Object[] arguments;
        try {
            arguments = argumentsFor(method);
        } catch (IllegalStateException e) {
            return new Result(Outcome.ERROR, "no argument recipe: " + e.getMessage());
        }
        try {
            Object value = method.invoke(meta, arguments);
            if (value instanceof ResultSet) {
                return describeResultSet((ResultSet) value);
            }
            return new Result(Outcome.VALUE, render(value));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLFeatureNotSupportedException) {
                return new Result(Outcome.UNSUPPORTED, cause.getMessage());
            }
            return new Result(
                    Outcome.ERROR, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (ReflectiveOperationException e) {
            return new Result(Outcome.ERROR, e.toString());
        }
    }

    /**
     * Drains a metadata result set and closes it.
     *
     * <p>Closing before returning is not tidiness: metadata queries run through the connection's
     * one statement slot, and leaving one open would make the next scanned method wait on it.
     */
    private Result describeResultSet(ResultSet rs) {
        try {
            int columns = rs.getMetaData().getColumnCount();
            long rows = 0;
            while (rs.next()) {
                rows++;
            }
            if (rows == 0) {
                return new Result(Outcome.EMPTY_RESULT_SET, "0 rows, " + columns + " columns");
            }
            return new Result(Outcome.VALUE, rows + " rows, " + columns + " columns");
        } catch (SQLException e) {
            return new Result(Outcome.ERROR, "reading rows: " + e.getMessage());
        } finally {
            try {
                rs.close();
            } catch (SQLException ignored) {
                // A close failure would show up as a leaked handle in assertNoLeakedHandles().
            }
        }
    }

    /** Calls every {@link ResultSetMetaData} accessor for every column, and fails if one throws. */
    private static void probeColumnMetadata(String method, ResultSetMetaData rsmd)
            throws SQLException {
        assertTrue(rsmd.getColumnCount() > 0, method + " reported no columns");
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
            String where = method + " column " + i;
            try {
                assertNotNull(rsmd.getColumnLabel(i), where + " label");
                assertNotNull(rsmd.getColumnName(i), where + " name");
                assertNotNull(rsmd.getColumnTypeName(i), where + " type name");
                assertNotNull(rsmd.getColumnClassName(i), where + " class name");
                rsmd.getColumnType(i);
                rsmd.getColumnDisplaySize(i);
                rsmd.getPrecision(i);
                rsmd.getScale(i);
                rsmd.isNullable(i);
                rsmd.isSigned(i);
                rsmd.isCaseSensitive(i);
                rsmd.isSearchable(i);
                rsmd.isCurrency(i);
                rsmd.isAutoIncrement(i);
                rsmd.isReadOnly(i);
                rsmd.isWritable(i);
                rsmd.isDefinitelyWritable(i);
                assertNotNull(rsmd.getTableName(i), where + " table name");
                assertNotNull(rsmd.getSchemaName(i), where + " schema name");
                assertNotNull(rsmd.getCatalogName(i), where + " catalog name");
            } catch (SQLException e) {
                fail(where + " metadata threw: " + e);
            }
        }
    }

    // ------------------------------------------------------------- arguments

    /**
     * Arguments for a scanned method.
     *
     * <p>Strings default to {@code null}, which in JDBC's pattern parameters means "do not
     * filter" and is what a tool listing everything passes. The methods scoped to one table get
     * the table this test created, so their answer is a real one rather than an empty set that
     * happens to be empty because nothing matched. Numeric parameters get the value the driver
     * actually supports, since asking {@code supportsResultSetType(TYPE_SCROLL_SENSITIVE)} only
     * proves the driver can say no.
     */
    private static Object[] argumentsFor(Method method) {
        Object[] override = ARGUMENTS.get(signature(method));
        if (override != null) {
            return override.clone();
        }
        Class<?>[] types = method.getParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                arguments[i] = null;
            } else if (types[i] == int.class) {
                arguments[i] = 0;
            } else if (types[i] == boolean.class) {
                arguments[i] = false;
            } else if (!types[i].isPrimitive()) {
                arguments[i] = null;
            } else {
                throw new IllegalStateException(signature(method));
            }
        }
        return arguments;
    }

    private static final Map<String, Object[]> ARGUMENTS = new HashMap<>();

    static {
        // Table-scoped calls, pointed at the table the scan creates. Passing the real names is
        // what makes getColumns and getPrimaryKeys exercise their populated path.
        ARGUMENTS.put("getColumns(String,String,String,String)", new Object[] {null, SCHEMA, TABLE, null});
        ARGUMENTS.put("getPrimaryKeys(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put("getImportedKeys(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put("getExportedKeys(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put("getVersionColumns(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put(
                "getColumnPrivileges(String,String,String,String)",
                new Object[] {null, SCHEMA, TABLE, null});
        ARGUMENTS.put("getTablePrivileges(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put("getSuperTables(String,String,String)", new Object[] {null, SCHEMA, TABLE});
        ARGUMENTS.put(
                "getPseudoColumns(String,String,String,String)",
                new Object[] {null, SCHEMA, TABLE, null});
        ARGUMENTS.put(
                "getIndexInfo(String,String,String,boolean,boolean)",
                new Object[] {null, SCHEMA, TABLE, false, true});
        ARGUMENTS.put(
                "getBestRowIdentifier(String,String,String,int,boolean)",
                new Object[] {null, SCHEMA, TABLE, DatabaseMetaData.bestRowSession, true});
        ARGUMENTS.put(
                "getCrossReference(String,String,String,String,String,String)",
                new Object[] {null, SCHEMA, TABLE, null, SCHEMA, TABLE});

        // Capability probes, asked about what the driver supports rather than what it does not.
        int forwardOnly = ResultSet.TYPE_FORWARD_ONLY;
        ARGUMENTS.put("supportsResultSetType(int)", new Object[] {forwardOnly});
        ARGUMENTS.put(
                "supportsResultSetConcurrency(int,int)",
                new Object[] {forwardOnly, ResultSet.CONCUR_READ_ONLY});
        ARGUMENTS.put(
                "supportsResultSetHoldability(int)",
                new Object[] {ResultSet.CLOSE_CURSORS_AT_COMMIT});
        ARGUMENTS.put(
                "supportsTransactionIsolationLevel(int)",
                new Object[] {Connection.TRANSACTION_NONE});
        ARGUMENTS.put("supportsConvert(int,int)", new Object[] {Types.INTEGER, Types.VARCHAR});
        for (String name :
                new String[] {
                    "ownUpdatesAreVisible", "ownDeletesAreVisible", "ownInsertsAreVisible",
                    "othersUpdatesAreVisible", "othersDeletesAreVisible", "othersInsertsAreVisible",
                    "updatesAreDetected", "deletesAreDetected", "insertsAreDetected"
                }) {
            ARGUMENTS.put(name + "(int)", new Object[] {forwardOnly});
        }

        // Wrapper. A GUI does this to reach a driver's own extensions.
        ARGUMENTS.put("unwrap(Class)", new Object[] {DatabaseMetaData.class});
        ARGUMENTS.put("isWrapperFor(Class)", new Object[] {DatabaseMetaData.class});
    }

    // ------------------------------------------------- the JDBC column names

    /**
     * The column names JDBC 4.3 specifies for each catalog query, in spec order.
     *
     * <p>Transcribed from the {@link DatabaseMetaData} javadoc rather than from this driver, so
     * that a driver-side rename is a test failure. Only the specified columns are listed; JDBC
     * permits extra ones after them.
     */
    private static final Map<String, List<String>> JDBC_COLUMNS = new HashMap<>();

    static {
        JDBC_COLUMNS.put("getCatalogs", Arrays.asList("TABLE_CAT"));
        JDBC_COLUMNS.put("getSchemas", Arrays.asList("TABLE_SCHEM", "TABLE_CATALOG"));
        JDBC_COLUMNS.put("getTableTypes", Arrays.asList("TABLE_TYPE"));
        JDBC_COLUMNS.put(
                "getTables",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME",
                        "REF_GENERATION"));
        JDBC_COLUMNS.put(
                "getColumns",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
                        "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
                        "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                        "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                        "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                        "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"));
        JDBC_COLUMNS.put(
                "getTypeInfo",
                Arrays.asList(
                        "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
                        "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE",
                        "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT",
                        "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE",
                        "SQL_DATETIME_SUB", "NUM_PREC_RADIX"));
        JDBC_COLUMNS.put(
                "getPrimaryKeys",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ",
                        "PK_NAME"));
        List<String> foreignKeys =
                Arrays.asList(
                        "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
                        "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ",
                        "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
        JDBC_COLUMNS.put("getImportedKeys", foreignKeys);
        JDBC_COLUMNS.put("getExportedKeys", foreignKeys);
        JDBC_COLUMNS.put("getCrossReference", foreignKeys);
        JDBC_COLUMNS.put(
                "getIndexInfo",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
                        "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
                        "CARDINALITY", "PAGES", "FILTER_CONDITION"));
        JDBC_COLUMNS.put(
                "getProcedures",
                Arrays.asList(
                        "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "REMARKS",
                        "PROCEDURE_TYPE", "SPECIFIC_NAME"));
        JDBC_COLUMNS.put(
                "getProcedureColumns",
                Arrays.asList(
                        "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME",
                        "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE",
                        "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                        "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                        "SPECIFIC_NAME"));
        JDBC_COLUMNS.put(
                "getFunctions",
                Arrays.asList(
                        "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS",
                        "FUNCTION_TYPE", "SPECIFIC_NAME"));
        JDBC_COLUMNS.put(
                "getFunctionColumns",
                Arrays.asList(
                        "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME",
                        "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE",
                        "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                        "IS_NULLABLE", "SPECIFIC_NAME"));
        JDBC_COLUMNS.put(
                "getTablePrivileges",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
                        "IS_GRANTABLE"));
        JDBC_COLUMNS.put(
                "getColumnPrivileges",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR",
                        "GRANTEE", "PRIVILEGE", "IS_GRANTABLE"));
        List<String> rowIdentifier =
                Arrays.asList(
                        "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
                        "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
        JDBC_COLUMNS.put("getBestRowIdentifier", rowIdentifier);
        JDBC_COLUMNS.put("getVersionColumns", rowIdentifier);
        JDBC_COLUMNS.put(
                "getUDTs",
                Arrays.asList(
                        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE",
                        "REMARKS", "BASE_TYPE"));
        JDBC_COLUMNS.put(
                "getSuperTypes",
                Arrays.asList(
                        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM",
                        "SUPERTYPE_NAME"));
        JDBC_COLUMNS.put(
                "getSuperTables",
                Arrays.asList("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"));
        JDBC_COLUMNS.put(
                "getAttributes",
                Arrays.asList(
                        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE",
                        "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                        "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
                        "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG",
                        "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"));
        JDBC_COLUMNS.put(
                "getClientInfoProperties",
                Arrays.asList("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"));
        JDBC_COLUMNS.put(
                "getPseudoColumns",
                Arrays.asList(
                        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
                        "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE",
                        "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE"));
    }

    // ---------------------------------------------------------------- output

    private static List<String> labels(ResultSetMetaData rsmd) throws SQLException {
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
            labels.add(rsmd.getColumnLabel(i));
        }
        return labels;
    }

    private static String signature(Method method) {
        StringBuilder text = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(types[i].getSimpleName());
        }
        return text.append(')').toString();
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        String text = value.toString().replace('\n', ' ');
        return text.length() > 70 ? text.substring(0, 67) + "..." : text;
    }

    private static List<String> namesWithOutcome(Map<String, Result> results, Outcome outcome) {
        List<String> names = new ArrayList<>();
        results.forEach(
                (name, result) -> {
                    if (result.outcome == outcome) {
                        names.add(name);
                    }
                });
        return names;
    }

    private static String errorDetail(Map<String, Result> results) {
        StringBuilder text = new StringBuilder();
        results.forEach(
                (name, result) -> {
                    if (result.outcome == Outcome.ERROR) {
                        text.append("\n  ").append(name).append(" -> ").append(result.detail);
                    }
                });
        return text.toString();
    }

    /** A markdown table, so the scan's output can be pasted into docs/unsupported.md as is. */
    private static String report(Map<String, Result> results) {
        Map<Outcome, Integer> counts = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0);
        }
        StringBuilder text = new StringBuilder();
        // The JDK is named because the size of the surface is its property, not the driver's,
        // and a scan report that does not say which JDK produced it cannot be compared with
        // another one.
        text.append("\n=== DatabaseMetaData surface scan (issue #11) ===\n")
                .append("JDK ")
                .append(System.getProperty("java.version"))
                .append(", ")
                .append(DatabaseMetaData.class.getMethods().length)
                .append(" methods on the interface\n\n");
        text.append("| Method | Outcome | Detail |\n|---|---|---|\n");
        results.forEach(
                (name, result) -> {
                    counts.merge(result.outcome, 1, Integer::sum);
                    text.append("| `")
                            .append(name)
                            .append("` | ")
                            .append(result.outcome)
                            .append(" | ")
                            .append(render(result.detail))
                            .append(" |\n");
                });
        text.append('\n');
        counts.forEach((outcome, count) -> text.append(outcome).append(": ").append(count).append('\n'));
        text.append("total: ").append(results.size()).append('\n');
        return text.toString();
    }
}
