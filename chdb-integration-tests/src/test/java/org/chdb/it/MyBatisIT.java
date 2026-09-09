package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The driver under MyBatis 3 (issue #11).
 *
 * <p>Issue #11 puts MyBatis and jOOQ in the same bracket for one reason: they read
 * {@link java.sql.ResultSetMetaData} and {@link java.sql.DatabaseMetaData} far more heavily than
 * anything else tested here. MyBatis's automatic result mapping is built entirely on
 * {@code ResultSetMetaData} -- for every column of every row it wants the label, the JDBC type
 * and the Java class name, and it picks a {@code TypeHandler} from those before it reads a
 * single value. A driver that reports {@code Types.OTHER} for a column it can actually read
 * would be mapped through the wrong handler and produce silently wrong data rather than an
 * error.
 *
 * <p>The configuration is built in Java rather than from a mapper XML, which is the same
 * {@code MappedStatement} machinery an XML mapper produces and keeps the test to one file.
 *
 * <p>The finding this test exists to record is in {@link #jdbcTransactionFactoryNeedsAutoCommit()}:
 * MyBatis's default {@link JdbcTransactionFactory} calls {@code setAutoCommit(false)} the moment
 * a session is opened without {@code autoCommit=true}, and this driver refuses that. See
 * {@code docs/unsupported.md}.
 */
class MyBatisIT extends NativeTestBase {

    /** A DataSource over the driver. Unpooled, because HikariPoolIT already covers pooling. */
    private static UnpooledDataSource dataSource() {
        UnpooledDataSource dataSource =
                new UnpooledDataSource("org.chdb.jdbc.ChdbDriver", MEMORY_URL, new Properties());
        // MyBatis would otherwise call setAutoCommit on every connection it opens.
        dataSource.setAutoCommit(true);
        return dataSource;
    }

    /**
     * A factory whose statements are registered programmatically.
     *
     * @param transactions {@link ManagedTransactionFactory} leaves transaction control to the
     *     caller, which for a driver with no transactions means it does nothing at all.
     */
    private static SqlSessionFactory factory(TransactionFactory transactions) {
        Configuration configuration =
                new Configuration(new Environment("chdb", transactions, dataSource()));
        configuration.setMapUnderscoreToCamelCase(false);
        // Without this MyBatis omits null-valued columns from a returned Map, which would make
        // "the driver returned no value" and "the driver returned NULL" indistinguishable here.
        configuration.setCallSettersOnNulls(true);
        register(configuration, "selectConstant", "SELECT 7 AS seven, 'x' AS letter");
        register(
                configuration,
                "selectNumbers",
                "SELECT number AS n, toString(number) AS s FROM numbers(#{limit})");
        register(
                configuration,
                "selectNumbersCast",
                "SELECT number AS n FROM numbers(toUInt64(#{limit}))");
        register(
                configuration,
                "selectBelow",
                "SELECT number AS n, toString(number) AS s FROM numbers(100)"
                        + " WHERE number < #{cutoff} ORDER BY number");
        register(configuration, "selectNothing", "SELECT 1 AS n WHERE 0");
        register(
                configuration,
                "selectTypes",
                "SELECT toInt32(1) AS i, toFloat64(1.5) AS d, toString('s') AS t,"
                        + " toDate('2024-01-02') AS dt, CAST(NULL AS Nullable(Int32)) AS nil");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** Registers one SELECT as a MyBatis MappedStatement returning Map rows. */
    private static void register(Configuration configuration, String id, String sql) {
        register(configuration, SqlCommandType.SELECT, id, sql);
    }

    /** Registers one statement as a MyBatis MappedStatement of the given command type. */
    private static void register(
            Configuration configuration, SqlCommandType command, String id, String sql) {
        // #{limit} has to become a real parameter mapping; MyBatis's XML builder does this from
        // the same StaticSqlSource, so what runs here is the production path.
        List<ParameterMapping> parameters = new ArrayList<>();
        StringBuilder rewritten = new StringBuilder();
        int cursor = 0;
        while (true) {
            int open = sql.indexOf("#{", cursor);
            if (open < 0) {
                rewritten.append(sql, cursor, sql.length());
                break;
            }
            int close = sql.indexOf('}', open);
            rewritten.append(sql, cursor, open).append('?');
            parameters.add(
                    new ParameterMapping.Builder(
                                    configuration, sql.substring(open + 2, close), Object.class)
                            .build());
            cursor = close + 1;
        }
        SqlSource source =
                new StaticSqlSource(configuration, rewritten.toString(), parameters);
        ResultMap resultMap =
                new ResultMap.Builder(configuration, id + "-result", Map.class, Collections.emptyList())
                        .build();
        ParameterMap parameterMap =
                new ParameterMap.Builder(configuration, id + "-params", Object.class, parameters)
                        .build();
        configuration.addMappedStatement(
                new MappedStatement.Builder(configuration, id, source, command)
                        .statementType(StatementType.PREPARED)
                        .parameterMap(parameterMap)
                        .resultMaps(Arrays.asList(resultMap))
                        .build());
    }

    @Test
    @DisplayName("MyBatis maps a result set through ResultSetMetaData without a type handler miss")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void automaticMappingWorks() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession()) {
            List<Map<String, Object>> rows = session.selectList("selectConstant");
            assertEquals(1, rows.size());
            // The keys are the column labels MyBatis read off ResultSetMetaData, so this
            // asserts the labels as much as the values.
            assertEquals(7, ((Number) rows.get(0).get("seven")).intValue());
            assertEquals("x", rows.get(0).get("letter"));
        }
    }

    @Test
    @DisplayName("a MyBatis #{} parameter binds, and works wherever a String literal is accepted")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void parameterBinding() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession()) {
            // MyBatis rewrites #{cutoff} to a ? and binds it, which lands on this driver's
            // PreparedStatement.
            List<Map<String, Object>> rows =
                    session.selectList("selectBelow", Collections.singletonMap("cutoff", 5));
            assertEquals(5, rows.size());
            assertEquals("4", rows.get(4).get("s"));
        }
    }

    @Test
    @DisplayName("a MyBatis #{} parameter in a numeric position is refused by the engine")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void parameterInANumericPositionIsRefused() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession()) {
            // The finding. Every parameter this driver binds is typed String, because the
            // engine's binding call is chdb_query_with_params_n and its values are text. That
            // is fine in a comparison, where ClickHouse converts the literal to the column's
            // type -- parameterBinding() above is a bound String against a UInt64 column. It is
            // not fine where ClickHouse requires a numeric constant, and #{} is how a MyBatis
            // user naturally parameterises a limit or a table-function argument.
            //
            // Measured on engine 26.7.0: numbers('5') gives "Code: 43. Illegal type String
            // expression, must be numeric type"; LIMIT '5' gives "Code: 440. LIMIT expression
            // must be constant with numeric type".
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    session.selectList(
                                            "selectNumbers", Collections.singletonMap("limit", 5)));
            assertTrue(
                    String.valueOf(rootCause(e).getMessage()).contains("must be numeric type"),
                    "expected the engine's numeric-type complaint, got " + rootCause(e));

            // The workaround to document: cast the parameter in the SQL.
            List<Map<String, Object>> rows =
                    session.selectList("selectNumbersCast", Collections.singletonMap("limit", 5));
            assertEquals(5, rows.size());
        }
    }

    @Test
    @DisplayName("an empty result set gives MyBatis an empty list rather than an error")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void emptyResultSet() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession()) {
            // MyBatis still builds its ResultSetWrapper from ResultSetMetaData here, which is
            // the zero-row metadata path issue #11 names.
            assertTrue(session.selectList("selectNothing").isEmpty());
        }
    }

    @Test
    @DisplayName("MyBatis picks a type handler per column from the driver's reported JDBC types")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void typeHandlerSelection() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession()) {
            List<Map<String, Object>> rows = session.selectList("selectTypes");
            assertEquals(1, rows.size());
            Map<String, Object> row = rows.get(0);
            // Each of these came back through the TypeHandler MyBatis chose from
            // ResultSetMetaData.getColumnType(), so the assertion is on the mapping as well as
            // the value.
            assertEquals(Integer.class, row.get("i").getClass());
            assertEquals(Double.class, row.get("d").getClass());
            assertEquals(String.class, row.get("t").getClass());
            assertNotNull(row.get("dt"));
            assertTrue(row.containsKey("nil"), "a NULL column must still appear");
            assertEquals(null, row.get("nil"));
        }
    }

    @Test
    @DisplayName("MyBatis's default JdbcTransactionFactory needs openSession(true)")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void jdbcTransactionFactoryNeedsAutoCommit() {
        SqlSessionFactory sessions = factory(new JdbcTransactionFactory());

        // The finding. MyBatis's JdbcTransaction.openConnection() calls
        // setAutoCommit(desiredAutoCommit) on the connection it just took from the DataSource,
        // and openSession() with no argument means desiredAutoCommit=false. This driver refuses
        // that rather than pretending to have a transaction, so the session fails on its first
        // statement -- which is the right failure, but it is a configuration a MyBatis user
        // reaches by default and has to be documented rather than discovered.
        try (SqlSession session = sessions.openSession()) {
            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> session.selectList("selectConstant"));
            assertTrue(
                    rootCause(e) instanceof SQLFeatureNotSupportedException,
                    "expected the driver's refusal, got " + rootCause(e));
        }

        // And the working configuration, which is the one to document.
        try (SqlSession session = sessions.openSession(true)) {
            List<Map<String, Object>> rows = session.selectList("selectConstant");
            assertEquals(1, rows.size());
        }
    }

    @Test
    @DisplayName("MyBatis's commit and rollback on a session that has no transaction are no-ops")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void commitAndRollbackUnderManagedTransactions() {
        try (SqlSession session = factory(new ManagedTransactionFactory()).openSession(true)) {
            session.selectList("selectConstant");
            // ManagedTransaction deliberately does nothing on commit/rollback, so a MyBatis
            // application that calls them -- most do -- does not reach Connection.commit() and
            // does not hit this driver's refusal. Recording it because it is the difference
            // between the two factories and the reason the workaround above works.
            session.commit();
            session.rollback();
        }
    }

    @Test
    @DisplayName("MyBatis inserts through its update path and reads the rows back")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void writeAndReadBack() throws SQLException {
        // One connection held open for the whole test on purpose. :memory: lives as long as the
        // storage path is bound, and the path is released when the last connection closes -- so
        // creating the table on a connection that is then closed, before MyBatis opens its own,
        // loses the table. Holding this one keeps the path bound across both.
        try (Connection anchor = openMemory()) {
            try (Statement statement = anchor.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS mybatis_probe");
                statement.execute(
                        "CREATE TABLE mybatis_probe (id UInt32, name String) ENGINE = Memory");
            }

            SqlSessionFactory sessions = factory(new ManagedTransactionFactory());
            try (SqlSession session = sessions.openSession(true)) {
                Configuration configuration = session.getConfiguration();
                register(
                        configuration,
                        SqlCommandType.INSERT,
                        "insertRow",
                        "INSERT INTO mybatis_probe VALUES (1, 'a'), (2, 'b')");
                register(configuration, "readRows", "SELECT id, name FROM mybatis_probe ORDER BY id");
                // insert() goes through MyBatis's update path, which reads
                // Statement.getUpdateCount() rather than a ResultSet. The count has to be the
                // real one: a MyBatis application checks the return value of an insert, and a
                // driver that answered 0 would have it conclude nothing was written.
                assertEquals(2, session.insert("insertRow"));

                List<Map<String, Object>> rows = session.selectList("readRows");
                assertEquals(2, rows.size());
                assertEquals("b", rows.get(1).get("name"));
            }

            try (Statement statement = anchor.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS mybatis_probe");
            }
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
