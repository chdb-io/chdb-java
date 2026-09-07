package org.chdb.examples;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Everything a first-time user needs, in one runnable file.
 *
 * <pre>
 * mvn -pl chdb-jdbc compile
 * scripts/build-native.sh &lt;platform&gt;
 * mvn -pl chdb-examples -am compile
 * mvn -pl chdb-examples exec:java -Dexec.mainClass=org.chdb.examples.QuickStart
 * </pre>
 *
 * Nothing here is contrived for the sake of the example: closing in the right order, binding
 * parameters instead of concatenating and streaming instead of collecting are what the driver
 * expects of ordinary code.
 */
public final class QuickStart {

    private QuickStart() {
    }

    public static void main(String[] args) throws SQLException {
        // No Class.forName: the driver registers itself through
        // META-INF/services/java.sql.Driver.
        //
        // ":memory:" is one database shared by every connection in this JVM, and it is gone
        // when the last of them closes. For storage that persists, use jdbc:chdb:/some/path.
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:")) {
            System.out.println("engine " + connection.getMetaData().getDatabaseProductVersion());

            createAndLoad(connection);
            queryWithParameters(connection);
            describeResultColumns(connection);
            streamALotOfRows(connection);
            queryFilesDirectly(connection);
            readAnUnsupportedTypeAsText(connection);
        }
    }

    /** DDL and DML go through execute/executeUpdate; only a SELECT has a result set. */
    private static void createAndLoad(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE orders ("
                            + "  id UInt64,"
                            + "  customer String,"
                            + "  amount Decimal(18, 2),"
                            + "  placed DateTime64(3, 'UTC'),"
                            + "  note Nullable(String)"
                            + ") ENGINE = MergeTree ORDER BY id");

            // One statement with many rows, not many statements: ClickHouse wants few large
            // inserts. This is also why the driver has no batch API.
            int inserted =
                    statement.executeUpdate(
                            "INSERT INTO orders VALUES"
                                    + " (1, 'Ada',    99.95,  '2026-09-01 10:00:00.000', 'first'),"
                                    + " (2, 'Grace', 149.50,  '2026-09-02 11:30:00.000', NULL),"
                                    + " (3, 'Ada',    12.00,  '2026-09-03 09:15:00.000', 'repeat'),"
                                    + " (4, 'Alan',  500.00,  '2026-09-03 16:45:00.000', NULL)");
            System.out.println("inserted " + inserted + " rows");
        }
    }

    /**
     * Parameters are bound by the engine. The value never enters the SQL text, so no quoting or
     * escaping is needed and none is possible to get wrong -- including for a value that looks
     * exactly like SQL.
     */
    private static void queryWithParameters(Connection connection) throws SQLException {
        String sql =
                "SELECT customer, count() AS orders, sum(amount) AS total"
                        + " FROM orders"
                        + " WHERE amount >= toDecimal64(?, 2) AND placed >= toDateTime64(?, 3, 'UTC')"
                        + " GROUP BY customer"
                        + " ORDER BY total DESC";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, new BigDecimal("50.00"));
            statement.setString(2, "2026-09-01 00:00:00");

            try (ResultSet rs = statement.executeQuery()) {
                System.out.println("\ncustomer  orders  total");
                while (rs.next()) {
                    System.out.printf(
                            "%-9s %6d  %s%n",
                            rs.getString("customer"), rs.getInt("orders"), rs.getBigDecimal("total"));
                }
            }
        }

        // A value that would be SQL if it were interpolated is just a value.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count() FROM orders WHERE customer = ?")) {
            statement.setString(1, "'; DROP TABLE orders; --");
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                System.out.println("\nrows matching a SQL-shaped name: " + rs.getLong(1));
            }
        }
    }

    /** Types come from the Arrow schema, and are available before the first row is read. */
    private static void describeResultColumns(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM orders LIMIT 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            System.out.println("\ncolumn     chDB type              Java type");
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                System.out.printf(
                        "%-10s %-22s %s%s%n",
                        meta.getColumnName(i),
                        meta.getColumnTypeName(i),
                        meta.getColumnClassName(i),
                        meta.isNullable(i) == ResultSetMetaData.columnNullable ? " (nullable)" : "");
            }
        }

        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM orders ORDER BY id LIMIT 2")) {
            System.out.println();
            while (rs.next()) {
                // getObject returns the exact temporal types; a java.sql.Timestamp would need
                // a timezone to be meaningful.
                LocalDate placedOn = rs.getObject("placed", LocalDate.class);
                Instant placedAt = rs.getObject("placed", Instant.class);
                // wasNull() reports on the single most recent read, so it has to be checked
                // straight after the accessor it refers to. Reading it from inside an argument
                // list would report on whichever argument javac happened to evaluate last.
                String note = rs.getString("note");
                String noteOrNull = rs.wasNull() ? "<null>" : note;

                System.out.printf(
                        "order %d  %s  %s  on %s at %s  note=%s%n",
                        rs.getLong("id"),
                        rs.getString("customer"),
                        rs.getBigDecimal("amount"),
                        placedOn,
                        placedAt,
                        noteOrNull);
            }
        }
    }

    /**
     * A result far larger than the heap. One Arrow batch is live at a time, so peak memory
     * tracks the batch rather than the result -- provided the rows are not accumulated here.
     */
    private static void streamALotOfRows(Connection connection) throws SQLException {
        long start = System.nanoTime();
        long rows = 0;
        long sum = 0;

        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT number FROM numbers(10000000)")) {
            while (rs.next()) {
                sum += rs.getLong(1);
                rows++;
            }
        }

        System.out.printf(
                "%nstreamed %,d rows (sum %,d) in %d ms%n",
                rows, sum, (System.nanoTime() - start) / 1_000_000);

        // Reading part of a huge result and stopping is cheap: closing cancels the query
        // instead of draining it.
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(3);
            try (ResultSet rs = statement.executeQuery("SELECT number FROM numbers(100000000000)")) {
                while (rs.next()) {
                    System.out.println("  row " + rs.getLong(1) + " of an effectively endless result");
                }
            }
        }
    }

    /** The reason to embed chDB: SQL over files and URLs with no loading step. */
    private static void queryFilesDirectly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT toUInt32(number % 3) AS bucket, count() AS n"
                                        + " FROM numbers(1000) GROUP BY bucket ORDER BY bucket")) {
            System.out.println("\ngrouped without a table:");
            while (rs.next()) {
                System.out.println("  bucket " + rs.getInt("bucket") + " -> " + rs.getLong("n"));
            }
        }

        // The same shape works over real data, with no INSERT:
        //   SELECT ... FROM file('events.parquet', 'Parquet')
        //   SELECT ... FROM url('https://host/logs.jsonl', 'JSONEachRow')
        //   SELECT ... FROM s3('s3://bucket/*.parquet', 'key', 'secret', 'Parquet')
        //   SELECT ... FROM postgresql('host:5432', 'db', 'table', 'user', 'password')
        System.out.println("  (file(), url(), s3() and postgresql() work the same way)");
    }

    /**
     * V1 reads scalar types. A column it cannot read is a typed error naming the workaround,
     * not a wrong value -- and the workaround is a cast in SQL.
     */
    private static void readAnUnsupportedTypeAsText(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT groupArray(customer) AS names FROM orders")) {
            rs.next();
            rs.getObject(1);
            System.out.println("\nunreachable: an Array column should not be readable");
        } catch (SQLException expected) {
            System.out.println("\nArray column: " + firstLine(expected.getMessage()));
        }

        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT toString(groupArray(customer)) AS names FROM orders")) {
            rs.next();
            System.out.println("as text:      " + rs.getString("names"));
        }
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 100 ? line.substring(0, 97) + "..." : line;
    }
}
