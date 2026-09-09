package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The driver under jOOQ 3.16 (issue #11).
 *
 * <p>jOOQ is the heaviest reader of {@link java.sql.DatabaseMetaData} of anything tested here.
 * {@code DSLContext.meta()} walks {@code getCatalogs}, {@code getSchemas}, {@code getTables},
 * {@code getColumns}, {@code getPrimaryKeys} and {@code getIndexInfo} to build its own model,
 * and half of those return no rows in this driver -- which is exactly the empty-result-set path
 * issue #11 calls out, because jOOQ reads the column labels off the empty sets just as it does
 * off the populated ones.
 *
 * <p>jOOQ 3.16 rather than a current release: 3.17.0 is the first Open Source Edition compiled
 * for Java 17 (class file 61 in the published jar), and using it would drop Java 11 out of the
 * supported matrix. 3.16 also predates jOOQ's ClickHouse dialect, so everything here runs under
 * {@link SQLDialect#DEFAULT} -- which is the correct thing to test, since that is what jOOQ
 * gives an engine whose {@code getDatabaseProductName()} it does not recognise, and "chDB" is
 * not recognised by any jOOQ version.
 *
 * <p>Two findings came out of this and are in {@code docs/unsupported.md}:
 * {@link #limitAsABindValueIsRefusedByTheEngine()} and {@link #transactionsAreRefused()}.
 */
class JooqIT extends NativeTestBase {

    /**
     * jOOQ over one connection.
     *
     * <p>{@code withRenderSchema(false)} because ClickHouse's one namespace level is JDBC's
     * schema, and jOOQ under {@code DEFAULT} would otherwise qualify names with a catalog that
     * does not exist.
     */
    private static DSLContext context(Connection connection) {
        return DSL.using(
                connection,
                SQLDialect.DEFAULT,
                new Settings().withRenderSchema(false).withRenderCatalog(false));
    }

    @Test
    @DisplayName("jOOQ falls back to SQLDialect.DEFAULT and runs a query through the driver")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void plainQuery() throws SQLException {
        try (Connection connection = openMemory()) {
            // JDBCUtils.dialect() reads getDatabaseProductName(), gets "chDB", and finds no
            // match. DEFAULT is the honest outcome and it has to be a working one.
            assertEquals(SQLDialect.DEFAULT, org.jooq.impl.DSL.using(connection).dialect());

            DSLContext ctx = context(connection);
            Result<? extends Record> result =
                    ctx.select(DSL.field("number", Long.class))
                            .from(DSL.table("numbers(5)"))
                            .fetch();
            assertEquals(5, result.size());
            assertEquals(Long.valueOf(0L), result.get(0).get("number", Long.class));
            // format() reads every column's type off ResultSetMetaData, so it exercises the
            // metadata jOOQ built the Record from rather than just the values.
            assertNotNull(result.format());
        }
    }

    @Test
    @DisplayName("jOOQ reads a zero-row result set and still gets its column list")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void zeroRowResult() throws SQLException {
        try (Connection connection = openMemory()) {
            Result<Record> result = context(connection).fetch("SELECT toInt32(1) AS n WHERE 0");
            assertTrue(result.isEmpty());
            // The point of the test: jOOQ builds its Field list from ResultSetMetaData before
            // it reads a row, so an empty result must still describe itself. A driver that only
            // populates metadata once a batch arrives gives jOOQ zero fields here, and the
            // caller cannot tell an empty answer from a broken query.
            assertEquals(1, result.fields().length);
            assertEquals("n", result.field(0).getName());

            // Values still read back with an explicit type, whatever jOOQ inferred.
            Result<Record> populated = context(connection).fetch("SELECT toInt32(7) AS n");
            assertEquals(Integer.valueOf(7), populated.get(0).get("n", Integer.class));
        }
    }

    @Test
    @DisplayName("jOOQ types every chDB column as Object, because DEFAULT has no ClickHouse names")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void jooqCannotInferColumnTypes() throws SQLException {
        try (Connection connection = openMemory()) {
            // A finding, and not one the driver can fix. The driver reports this column
            // correctly -- ResultSetMetaData says type 4 (INTEGER), type name "Int32", class
            // name java.lang.Integer, on a populated and on a zero-row set alike, asserted
            // below. jOOQ resolves a field's Java type from the type *name* against its dialect
            // registry, and SQLDialect.DEFAULT has no entry for "Int32", so Field.getType() is
            // Object and jOOQ's inferred coercion is unavailable.
            //
            // Consequence for a jOOQ user: declare the type at the call site --
            // DSL.field("n", Integer.class), or Record.get(name, Integer.class) -- rather than
            // relying on jOOQ having inferred it. Every other test in this class does that.
            Result<Record> result = context(connection).fetch("SELECT toInt32(1) AS n");
            Field<?> field = result.field(0);
            assertEquals(Object.class, field.getType(), "jOOQ 3.16 under DEFAULT cannot type Int32");

            // And the driver's own answer, which is the correct one.
            try (Statement statement = connection.createStatement();
                    java.sql.ResultSet rs =
                            statement.executeQuery("SELECT toInt32(1) AS n WHERE 0")) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                assertEquals(java.sql.Types.INTEGER, meta.getColumnType(1));
                assertEquals("Int32", meta.getColumnTypeName(1));
                assertEquals("java.lang.Integer", meta.getColumnClassName(1));
            }
        }
    }

    @Test
    @DisplayName("jOOQ's meta() walks the whole catalog surface, empty result sets included")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void metaWalk() throws SQLException {
        try (Connection connection = openMemory()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS jooq_probe");
                statement.execute(
                        "CREATE TABLE jooq_probe (id UInt64, label String) ENGINE = Memory");
            }

            DSLContext ctx = context(connection);
            // getCatalogs() has no rows here, so jOOQ synthesises one; getSchemas() has rows.
            assertFalse(ctx.meta().getSchemas().isEmpty(), "jOOQ found no schemas");

            // getTables + getColumns + getPrimaryKeys + getIndexInfo per table. Restricted to
            // the default database because the engine's system tables would make jOOQ describe
            // a hundred and sixty of them.
            List<Table<?>> tables = ctx.meta().getTables("jooq_probe");
            assertEquals(1, tables.size(), tables.toString());
            Table<?> table = tables.get(0);
            assertEquals(2, table.fields().length);
            assertNotNull(table.field("id"));

            // getPrimaryKeys and getIndexInfo both come back empty -- ClickHouse has neither in
            // the JDBC sense. jOOQ has to end up with an empty key list rather than an error,
            // and this is the assertion that says the empty sets were parsed rather than merely
            // returned.
            assertTrue(table.getKeys().isEmpty(), "chDB has no JDBC primary keys");
            assertTrue(table.getIndexes().isEmpty(), "chDB has no JDBC indexes");

            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS jooq_probe");
            }
        }
    }

    @Test
    @DisplayName("jOOQ's default LIMIT is a bind value, and the engine refuses a String there")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void limitAsABindValueIsRefusedByTheEngine() throws SQLException {
        try (Connection connection = openMemory()) {
            DSLContext ctx = context(connection);

            // The finding, and the one that will bite a jOOQ user first, because .limit() is
            // the most-used clause jOOQ renders as a parameter rather than a literal.
            //
            // Every parameter this driver binds is typed String: the engine's parameter
            // binding is chdb_query_with_params_n, whose values are text, so `LIMIT ?` reaches
            // the engine as LIMIT '3' and ClickHouse requires a numeric constant there.
            // Measured on engine 26.7.0: "Code: 440. LIMIT expression must be constant with
            // numeric type. Actual: '3'".
            DataAccessException e =
                    assertThrows(
                            DataAccessException.class,
                            () ->
                                    ctx.select(DSL.field("number"))
                                            .from(DSL.table("numbers(10)"))
                                            .limit(3)
                                            .fetch());
            assertTrue(
                    String.valueOf(e.getMessage()).contains("440")
                            || String.valueOf(e.getCause()).contains("440"),
                    "expected the engine's LIMIT complaint, got " + e.getMessage());

            // The workaround, and the setting to tell a jOOQ user about: render the parameters
            // inline instead of binding them. jOOQ escapes them itself, so this is not a return
            // to string-concatenated SQL.
            DSLContext inlined =
                    DSL.using(
                            connection,
                            SQLDialect.DEFAULT,
                            new Settings()
                                    .withRenderSchema(false)
                                    .withRenderCatalog(false)
                                    .withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));
            Result<? extends Record> result =
                    inlined
                            .select(DSL.field("number", Long.class))
                            .from(DSL.table("numbers(10)"))
                            .limit(3)
                            .fetch();
            assertEquals(3, result.size());

            // And the same query rendered to SQL shows why: one has a ?, the other does not.
            String bound =
                    ctx.select(DSL.field("number"))
                            .from(DSL.table("numbers(10)"))
                            .limit(3)
                            .getSQL(ParamType.INDEXED);
            assertTrue(bound.contains("?"), bound);
        }
    }

    @Test
    @DisplayName("a parameter in a comparison works; a parameter in arithmetic does not")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void whereParametersWorkButArithmeticDoesNot() throws SQLException {
        try (Connection connection = openMemory()) {
            DSLContext ctx = context(connection);

            // A bound String against a numeric column is fine: ClickHouse converts the literal
            // to the column's type in a comparison. This is why jOOQ's WHERE clauses work and
            // its LIMIT does not, and the distinction is the useful half of the finding.
            Result<? extends Record> result =
                    ctx.select(DSL.field("number", Long.class))
                            .from(DSL.table("numbers(10)"))
                            .where(DSL.field("number").lt(DSL.val(4)))
                            .fetch();
            assertEquals(4, result.size());

            // Arithmetic has no such conversion: plus(UInt64, String) does not exist.
            assertThrows(
                    DataAccessException.class,
                    () ->
                            ctx.select(DSL.field("number", Long.class).add(DSL.val(1)))
                                    .from(DSL.table("numbers(3)"))
                                    .fetch());
        }
    }

    @Test
    @DisplayName("jOOQ's transaction() is refused, because the driver has no transactions")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void transactionsAreRefused() throws SQLException {
        try (Connection connection = openMemory()) {
            DSLContext ctx = context(connection);
            // DefaultTransactionProvider.begin() calls setAutoCommit(false) before it runs the
            // block. The driver refuses rather than pretending, so the block never runs -- which
            // is the right failure and not one jOOQ can be configured out of. A jOOQ
            // application against chDB has to not use transaction(), so it is documented.
            DataAccessException e =
                    assertThrows(
                            DataAccessException.class,
                            () -> ctx.transaction(configuration -> configuration.dsl().fetch("SELECT 1")));
            assertTrue(
                    rootCause(e) instanceof SQLFeatureNotSupportedException,
                    "expected the driver's refusal, got " + rootCause(e));
            // And the connection survives the refusal, so the application can carry on.
            assertEquals(1, ctx.fetch("SELECT 1").size());
        }
    }

    @Test
    @DisplayName("jOOQ inserts and reads back on one connection")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void insertAndSelect() throws SQLException {
        // One connection for the whole test on purpose: :memory: lives as long as the storage
        // path is bound, and the path is released when the last connection closes, so a table
        // created on a connection that is then closed is gone.
        try (Connection connection = openMemory()) {
            DSLContext ctx = context(connection);
            ctx.execute("DROP TABLE IF EXISTS jooq_write");
            ctx.execute("CREATE TABLE jooq_write (id UInt64, label String) ENGINE = Memory");
            // Rendered static so the values are inlined rather than bound, for the reason in
            // limitAsABindValueIsRefusedByTheEngine: a bound String into a UInt64 column is
            // converted on insert, but keeping one rendering mode across the test is clearer.
            ctx.execute("INSERT INTO jooq_write VALUES (1, 'a'), (2, 'b')");
            Result<? extends Record> rows =
                    ctx.select(DSL.field("id", Long.class), DSL.field("label", String.class))
                            .from(DSL.table("jooq_write"))
                            .orderBy(DSL.field("id"))
                            .fetch();
            assertEquals(2, rows.size());
            assertEquals("b", rows.get(1).get("label", String.class));
            ctx.execute("DROP TABLE IF EXISTS jooq_write");
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
