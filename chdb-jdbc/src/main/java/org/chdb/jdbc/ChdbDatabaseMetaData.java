package org.chdb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import org.chdb.internal.NativeLibraryLoader;

/**
 * The minimum {@link DatabaseMetaData} that JDBC frameworks actually read.
 *
 * <p>Work plan section 5.10 scopes this to what Spring's {@code JdbcTemplate}, HikariCP and
 * dialect detection need. The catalog queries -- {@link #getTables}, {@link #getColumns},
 * {@link #getSchemas}, {@link #getTypeInfo} -- are implemented as SQL against ClickHouse's
 * {@code system} tables, so their answers come from the engine rather than from a table baked
 * into the driver.
 *
 * <h2>Catalogs and schemas</h2>
 * ClickHouse has one level of namespace: the database. It is mapped to JDBC's <em>schema</em>,
 * not its catalog, because that is what tools expect of an engine with one level -- and
 * {@code getCatalogs()} therefore returns no rows.
 */
final class ChdbDatabaseMetaData implements DatabaseMetaData {

    private final ChdbConnection connection;

    ChdbDatabaseMetaData(ChdbConnection connection) {
        this.connection = connection;
    }

    /** Runs a metadata query and hands back its result set, which the caller closes. */
    private ResultSet query(String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            // closeOnCompletion so the caller only has to close the ResultSet, which is what
            // the DatabaseMetaData contract leads people to expect.
            statement.closeOnCompletion();
            return statement.executeQuery(sql);
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }

    /** Turns a JDBC LIKE pattern into a SQL predicate, or "true" when it matches everything. */
    private static String likeOrTrue(String column, String pattern) {
        if (pattern == null || "%".equals(pattern)) {
            return "1";
        }
        return column + " LIKE " + quote(pattern);
    }

    /** Single-quotes a string literal for SQL, doubling any quote inside it. */
    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String getDatabaseProductName() {
        return "chDB";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return NativeLibraryLoader.loadedRuntime().engineVersion();
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return versionPart(0);
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return versionPart(1);
    }

    /** Parses one dot-separated component out of the engine version, or 0. */
    private int versionPart(int index) throws SQLException {
        String[] parts = getDatabaseProductVersion().split("[.-]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String getDriverName() {
        return "chDB JDBC Driver";
    }

    @Override
    public String getDriverVersion() {
        return ChdbDriver.MAJOR_VERSION + "." + ChdbDriver.MINOR_VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return ChdbDriver.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return ChdbDriver.MINOR_VERSION;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public String getURL() {
        return connection.chdbUrl().url();
    }

    @Override
    public String getUserName() {
        // An embedded engine has no authenticated user.
        return "";
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    // ------------------------------------------------------------------ SQL dialect

    @Override
    public String getIdentifierQuoteString() {
        return "`";
    }

    @Override
    public String getSQLKeywords() {
        // ClickHouse keywords that are not SQL-92 reserved words, which is what this method
        // is defined to report. Trimmed to the ones a query builder needs to quote.
        return "ARRAY,ARRAYJOIN,ATTACH,CLUSTER,DETACH,DICTIONARY,ENGINE,FINAL,FORMAT,GLOBAL,"
                + "ILIKE,LIMIT,MATERIALIZED,OPTIMIZE,PREWHERE,SAMPLE,SETTINGS,TOTALS,TTL,USING";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public String getNumericFunctions() {
        return "abs,ceil,exp,floor,ln,log,log2,log10,pow,round,sign,sqrt,cbrt,greatest,least";
    }

    @Override
    public String getStringFunctions() {
        return "concat,empty,notEmpty,length,lower,upper,reverse,substring,trim,trimLeft,"
                + "trimRight,replaceAll,replaceOne,splitByChar,splitByString,position,match,"
                + "extract,format,leftPad,rightPad";
    }

    @Override
    public String getSystemFunctions() {
        return "currentDatabase,currentUser,hostName,version,uptime,timezone";
    }

    @Override
    public String getTimeDateFunctions() {
        return "now,today,yesterday,toDate,toDateTime,toDateTime64,toStartOfDay,toStartOfHour,"
                + "toStartOfMonth,toStartOfYear,dateDiff,dateAdd,dateSub,formatDateTime,"
                + "toUnixTimestamp,toYear,toMonth,toDayOfMonth,toHour,toMinute,toSecond";
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        // ClickHouse identifiers are stored exactly as written and compared case-sensitively.
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    // ------------------------------------------------------------------ transactions: none

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    // ------------------------------------------------------------------ result sets

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        // JDBC's named parameters are a CallableStatement feature, which V1 does not have.
        // chDB's own {name:Type} parameters are what PreparedStatement is built on, but they
        // are not reachable through the JDBC named-parameter API.
        return false;
    }

    // ------------------------------------------------------------------ SQL features

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return true;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        // ClickHouse sorts NULLs last by default (NULLS LAST), which for ascending order means
        // they sort high.
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    // ------------------------------------------------------------------ catalogs and schemas

    @Override
    public String getCatalogTerm() {
        return "catalog";
    }

    @Override
    public String getSchemaTerm() {
        return "database";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        // No rows: ClickHouse's one namespace level is reported as a schema, not a catalog.
        return query("SELECT '' AS TABLE_CAT WHERE 0");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return query(
                "SELECT name AS TABLE_SCHEM, '' AS TABLE_CATALOG"
                        + " FROM system.databases ORDER BY name");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return query(
                "SELECT name AS TABLE_SCHEM, '' AS TABLE_CATALOG"
                        + " FROM system.databases WHERE "
                        + likeOrTrue("name", schemaPattern)
                        + " ORDER BY name");
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT '' AS TABLE_CAT, database AS TABLE_SCHEM, name AS TABLE_NAME,")
                .append(" multiIf(engine = 'View', 'VIEW', engine = 'MaterializedView', 'VIEW',")
                .append(" is_temporary, 'LOCAL TEMPORARY', 'TABLE') AS TABLE_TYPE,")
                .append(" comment AS REMARKS, '' AS TYPE_CAT, '' AS TYPE_SCHEM, '' AS TYPE_NAME,")
                .append(" '' AS SELF_REFERENCING_COL_NAME, '' AS REF_GENERATION")
                .append(" FROM system.tables WHERE ")
                .append(likeOrTrue("database", schemaPattern))
                .append(" AND ")
                .append(likeOrTrue("name", tableNamePattern));
        if (types != null && types.length > 0) {
            StringBuilder inList = new StringBuilder();
            for (String type : types) {
                if (inList.length() > 0) {
                    inList.append(", ");
                }
                inList.append(quote(type));
            }
            sql.append(" AND TABLE_TYPE IN (").append(inList).append(')');
        }
        sql.append(" ORDER BY database, name");
        return query(sql.toString());
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return query(
                "SELECT arrayJoin(['TABLE', 'VIEW', 'LOCAL TEMPORARY']) AS TABLE_TYPE"
                        + " ORDER BY TABLE_TYPE");
    }

    /**
     * Column metadata from {@code system.columns}.
     *
     * <p>{@code DATA_TYPE} is left as {@link java.sql.Types#OTHER} and {@code TYPE_NAME} carries
     * the ClickHouse type name. Mapping the type name to a JDBC type here would mean
     * reimplementing the Arrow mapping against a different input -- a text type name rather
     * than an Arrow format string -- and the two would drift. A caller that needs the JDBC type
     * of a column can read it from {@code SELECT * FROM t LIMIT 0}'s
     * {@link java.sql.ResultSetMetaData}, which goes through the one mapping that matters.
     */
    @Override
    public ResultSet getColumns(
            String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, database AS TABLE_SCHEM, table AS TABLE_NAME,"
                        + " name AS COLUMN_NAME, "
                        + java.sql.Types.OTHER
                        + " AS DATA_TYPE, type AS TYPE_NAME,"
                        + " 0 AS COLUMN_SIZE, 0 AS BUFFER_LENGTH, 0 AS DECIMAL_DIGITS,"
                        + " 10 AS NUM_PREC_RADIX,"
                        + " if(startsWith(type, 'Nullable('), "
                        + columnNullable
                        + ", "
                        + columnNoNulls
                        + ") AS NULLABLE,"
                        + " comment AS REMARKS, default_expression AS COLUMN_DEF,"
                        + " 0 AS SQL_DATA_TYPE, 0 AS SQL_DATETIME_SUB, 0 AS CHAR_OCTET_LENGTH,"
                        + " toInt32(position) AS ORDINAL_POSITION,"
                        + " if(startsWith(type, 'Nullable('), 'YES', 'NO') AS IS_NULLABLE,"
                        + " '' AS SCOPE_CATALOG, '' AS SCOPE_SCHEMA, '' AS SCOPE_TABLE,"
                        + " 0 AS SOURCE_DATA_TYPE, 'NO' AS IS_AUTOINCREMENT,"
                        + " 'NO' AS IS_GENERATEDCOLUMN"
                        + " FROM system.columns WHERE "
                        + likeOrTrue("database", schemaPattern)
                        + " AND "
                        + likeOrTrue("table", tableNamePattern)
                        + " AND "
                        + likeOrTrue("name", columnNamePattern)
                        + " ORDER BY database, table, position");
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return query(
                "SELECT name AS TYPE_NAME, "
                        + java.sql.Types.OTHER
                        + " AS DATA_TYPE, 0 AS PRECISION,"
                        + " '' AS LITERAL_PREFIX, '' AS LITERAL_SUFFIX, '' AS CREATE_PARAMS,"
                        + " toInt16("
                        + typeNullableUnknown
                        + ") AS NULLABLE, 1 AS CASE_SENSITIVE,"
                        + " toInt16("
                        + typeSearchable
                        + ") AS SEARCHABLE, 0 AS UNSIGNED_ATTRIBUTE,"
                        + " 0 AS FIXED_PREC_SCALE, 0 AS AUTO_INCREMENT, name AS LOCAL_TYPE_NAME,"
                        + " toInt16(0) AS MINIMUM_SCALE, toInt16(0) AS MAXIMUM_SCALE,"
                        + " 0 AS SQL_DATA_TYPE, 0 AS SQL_DATETIME_SUB, 10 AS NUM_PREC_RADIX"
                        + " FROM system.data_type_families ORDER BY name");
    }

    // ClickHouse has no primary-key constraints, foreign keys, unique indexes, procedures,
    // privileges or UDTs in the JDBC sense. Each of these returns an empty result set with the
    // columns JDBC specifies: a framework that reads them gets a well-formed empty answer,
    // which is the truth, rather than an exception that would abort its introspection.

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME, '' AS COLUMN_NAME,"
                        + " toInt16(0) AS KEY_SEQ, '' AS PK_NAME WHERE 0");
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return emptyForeignKeys();
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return emptyForeignKeys();
    }

    @Override
    public ResultSet getCrossReference(
            String parentCatalog,
            String parentSchema,
            String parentTable,
            String foreignCatalog,
            String foreignSchema,
            String foreignTable)
            throws SQLException {
        return emptyForeignKeys();
    }

    private ResultSet emptyForeignKeys() throws SQLException {
        return query(
                "SELECT '' AS PKTABLE_CAT, '' AS PKTABLE_SCHEM, '' AS PKTABLE_NAME,"
                        + " '' AS PKCOLUMN_NAME, '' AS FKTABLE_CAT, '' AS FKTABLE_SCHEM,"
                        + " '' AS FKTABLE_NAME, '' AS FKCOLUMN_NAME, toInt16(0) AS KEY_SEQ,"
                        + " toInt16(0) AS UPDATE_RULE, toInt16(0) AS DELETE_RULE, '' AS FK_NAME,"
                        + " '' AS PK_NAME, toInt16(0) AS DEFERRABILITY WHERE 0");
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME, 0 AS NON_UNIQUE,"
                        + " '' AS INDEX_QUALIFIER, '' AS INDEX_NAME, toInt16(0) AS TYPE,"
                        + " toInt16(0) AS ORDINAL_POSITION, '' AS COLUMN_NAME, '' AS ASC_OR_DESC,"
                        + " 0 AS CARDINALITY, 0 AS PAGES, '' AS FILTER_CONDITION WHERE 0");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS PROCEDURE_CAT, '' AS PROCEDURE_SCHEM, '' AS PROCEDURE_NAME,"
                        + " '' AS RESERVED1, '' AS RESERVED2, '' AS RESERVED3, '' AS REMARKS,"
                        + " toInt16(0) AS PROCEDURE_TYPE, '' AS SPECIFIC_NAME WHERE 0");
    }

    @Override
    public ResultSet getProcedureColumns(
            String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS PROCEDURE_CAT, '' AS PROCEDURE_SCHEM, '' AS PROCEDURE_NAME,"
                        + " '' AS COLUMN_NAME, toInt16(0) AS COLUMN_TYPE, 0 AS DATA_TYPE,"
                        + " '' AS TYPE_NAME, 0 AS PRECISION, 0 AS LENGTH, toInt16(0) AS SCALE,"
                        + " toInt16(10) AS RADIX, toInt16(0) AS NULLABLE, '' AS REMARKS,"
                        + " '' AS COLUMN_DEF, 0 AS SQL_DATA_TYPE, 0 AS SQL_DATETIME_SUB,"
                        + " 0 AS CHAR_OCTET_LENGTH, 0 AS ORDINAL_POSITION, '' AS IS_NULLABLE,"
                        + " '' AS SPECIFIC_NAME WHERE 0");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS FUNCTION_CAT, '' AS FUNCTION_SCHEM, name AS FUNCTION_NAME,"
                        + " '' AS REMARKS, toInt16("
                        + functionNoTable
                        + ") AS FUNCTION_TYPE, name AS SPECIFIC_NAME"
                        + " FROM system.functions WHERE "
                        + likeOrTrue("name", functionNamePattern)
                        + " ORDER BY name");
    }

    @Override
    public ResultSet getFunctionColumns(
            String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
            throws SQLException {
        // system.functions does not describe signatures, and ClickHouse functions are heavily
        // overloaded, so there is nothing accurate to report.
        return query(
                "SELECT '' AS FUNCTION_CAT, '' AS FUNCTION_SCHEM, '' AS FUNCTION_NAME,"
                        + " '' AS COLUMN_NAME, toInt16(0) AS COLUMN_TYPE, 0 AS DATA_TYPE,"
                        + " '' AS TYPE_NAME, 0 AS PRECISION, 0 AS LENGTH, toInt16(0) AS SCALE,"
                        + " toInt16(10) AS RADIX, toInt16(0) AS NULLABLE, '' AS REMARKS,"
                        + " 0 AS CHAR_OCTET_LENGTH, 0 AS ORDINAL_POSITION, '' AS IS_NULLABLE,"
                        + " '' AS SPECIFIC_NAME WHERE 0");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME, '' AS GRANTOR,"
                        + " '' AS GRANTEE, '' AS PRIVILEGE, '' AS IS_GRANTABLE WHERE 0");
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME, '' AS COLUMN_NAME,"
                        + " '' AS GRANTOR, '' AS GRANTEE, '' AS PRIVILEGE, '' AS IS_GRANTABLE"
                        + " WHERE 0");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return query(
                "SELECT toInt16(0) AS SCOPE, '' AS COLUMN_NAME, 0 AS DATA_TYPE, '' AS TYPE_NAME,"
                        + " 0 AS COLUMN_SIZE, 0 AS BUFFER_LENGTH, toInt16(0) AS DECIMAL_DIGITS,"
                        + " toInt16(0) AS PSEUDO_COLUMN WHERE 0");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return query(
                "SELECT toInt16(0) AS SCOPE, '' AS COLUMN_NAME, 0 AS DATA_TYPE, '' AS TYPE_NAME,"
                        + " 0 AS COLUMN_SIZE, 0 AS BUFFER_LENGTH, toInt16(0) AS DECIMAL_DIGITS,"
                        + " toInt16(0) AS PSEUDO_COLUMN WHERE 0");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        return query(
                "SELECT '' AS TYPE_CAT, '' AS TYPE_SCHEM, '' AS TYPE_NAME, '' AS CLASS_NAME,"
                        + " 0 AS DATA_TYPE, '' AS REMARKS, toInt16(0) AS BASE_TYPE WHERE 0");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TYPE_CAT, '' AS TYPE_SCHEM, '' AS TYPE_NAME, '' AS SUPERTYPE_CAT,"
                        + " '' AS SUPERTYPE_SCHEM, '' AS SUPERTYPE_NAME WHERE 0");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME,"
                        + " '' AS SUPERTABLE_NAME WHERE 0");
    }

    @Override
    public ResultSet getAttributes(
            String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TYPE_CAT, '' AS TYPE_SCHEM, '' AS TYPE_NAME, '' AS ATTR_NAME,"
                        + " 0 AS DATA_TYPE, '' AS ATTR_TYPE_NAME, 0 AS ATTR_SIZE,"
                        + " 0 AS DECIMAL_DIGITS, 0 AS NUM_PREC_RADIX, 0 AS NULLABLE,"
                        + " '' AS REMARKS, '' AS ATTR_DEF, 0 AS SQL_DATA_TYPE,"
                        + " 0 AS SQL_DATETIME_SUB, 0 AS CHAR_OCTET_LENGTH, 0 AS ORDINAL_POSITION,"
                        + " '' AS IS_NULLABLE, '' AS SCOPE_CATALOG, '' AS SCOPE_SCHEMA,"
                        + " '' AS SCOPE_TABLE, toInt16(0) AS SOURCE_DATA_TYPE WHERE 0");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return query(
                "SELECT '' AS NAME, 0 AS MAX_LEN, '' AS DEFAULT_VALUE, '' AS DESCRIPTION WHERE 0");
    }

    @Override
    public ResultSet getPseudoColumns(
            String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return query(
                "SELECT '' AS TABLE_CAT, '' AS TABLE_SCHEM, '' AS TABLE_NAME, '' AS COLUMN_NAME,"
                        + " 0 AS DATA_TYPE, 0 AS COLUMN_SIZE, 0 AS DECIMAL_DIGITS,"
                        + " 0 AS NUM_PREC_RADIX, '' AS COLUMN_USAGE, '' AS REMARKS,"
                        + " 0 AS CHAR_OCTET_LENGTH, '' AS IS_NULLABLE WHERE 0");
    }

    // ------------------------------------------------------------------ limits

    // Zero means "no limit, or unknown", which is the honest answer for an engine that does
    // not publish these as fixed numbers.

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    // ------------------------------------------------------------------ misc

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean usesLocalFiles() {
        // The engine's storage is local files, which is what this method asks about.
        return !connection.chdbUrl().isMemory();
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    /**
     * SQL:2003 SQLSTATE values.
     *
     * <p>The driver's own codes are the standard classes -- {@code 42xxx} for syntax and access
     * violations, {@code 0A000} for unsupported features, {@code 22xxx} for data errors,
     * {@code 08xxx} for connection errors. A handful of operational states use the X/Open
     * {@code HYxxx} class, which has no SQL:2003 equivalent; reporting SQL:2003 is the closer
     * of the two answers.
     */
    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean supportsSharding() {
        // A single embedded engine. Distributed tables are a server feature.
        return false;
    }

    @Override
    public boolean supportsRefCursors() {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLFeatureNotSupportedException("Not a wrapper for " + iface.getName(), "0A000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
