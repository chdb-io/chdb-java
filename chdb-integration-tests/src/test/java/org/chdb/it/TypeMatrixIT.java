package org.chdb.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The V1 scalar type matrix from work plan section 5.9, read end to end through the engine. */
class TypeMatrixIT extends NativeTestBase {

    /** Runs a one-row query and hands the positioned result set to the assertions. */
    private void withRow(String sql, RowAssertions assertions) throws SQLException {
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "expected one row from: " + sql);
            assertions.check(rs, rs.getMetaData());
        }
    }

    private interface RowAssertions {
        void check(ResultSet rs, ResultSetMetaData meta) throws SQLException;
    }

    @Test
    @DisplayName("Bool maps to boolean")
    void booleanType() throws SQLException {
        withRow("SELECT true AS t, false AS f", (rs, meta) -> {
            assertEquals("Bool", meta.getColumnTypeName(1));
            assertEquals(Types.BOOLEAN, meta.getColumnType(1));
            assertEquals(Boolean.class.getName(), meta.getColumnClassName(1));
            assertTrue(rs.getBoolean("t"));
            assertFalse(rs.getBoolean("f"));
            assertEquals(Boolean.TRUE, rs.getObject("t"));
            // JDBC's numeric view of a boolean.
            assertEquals(1, rs.getInt("t"));
            assertEquals("true", rs.getString("t"));
        });
    }

    @Test
    @DisplayName("signed integers map to the matching Java types, at their extremes")
    void signedIntegers() throws SQLException {
        withRow(
                "SELECT toInt8(-128) AS a, toInt16(-32768) AS b, toInt32(-2147483648) AS c,"
                        + " toInt64(-9223372036854775808) AS d",
                (rs, meta) -> {
                    assertEquals(Types.TINYINT, meta.getColumnType(1));
                    assertEquals(Types.SMALLINT, meta.getColumnType(2));
                    assertEquals(Types.INTEGER, meta.getColumnType(3));
                    assertEquals(Types.BIGINT, meta.getColumnType(4));
                    assertTrue(meta.isSigned(1));

                    assertEquals((byte) -128, rs.getByte("a"));
                    assertEquals((short) -32768, rs.getShort("b"));
                    assertEquals(Integer.MIN_VALUE, rs.getInt("c"));
                    assertEquals(Long.MIN_VALUE, rs.getLong("d"));
                    assertEquals(Byte.valueOf((byte) -128), rs.getObject("a"));
                    assertEquals(Long.valueOf(Long.MIN_VALUE), rs.getObject("d"));
                });
    }

    @Test
    @DisplayName("unsigned integers widen so their full range is representable")
    void unsignedIntegers() throws SQLException {
        withRow(
                "SELECT toUInt8(255) AS a, toUInt16(65535) AS b, toUInt32(4294967295) AS c",
                (rs, meta) -> {
                    assertEquals(Types.SMALLINT, meta.getColumnType(1));
                    assertEquals(Types.INTEGER, meta.getColumnType(2));
                    assertEquals(Types.BIGINT, meta.getColumnType(3));
                    assertFalse(meta.isSigned(1));

                    assertEquals((short) 255, rs.getShort("a"));
                    assertEquals(65535, rs.getInt("b"));
                    assertEquals(4294967295L, rs.getLong("c"));
                });
    }

    @Test
    @DisplayName("a UInt32 that does not fit an int is refused, not silently wrapped")
    void unsignedNarrowingIsRefused() throws SQLException {
        withRow("SELECT toUInt32(4294967295) AS c", (rs, meta) -> {
            SQLException e = assertThrows(SQLException.class, () -> rs.getInt("c"));
            assertEquals("22003", e.getSQLState());
            assertTrue(e.getMessage().contains("does not fit a Java int"), e.getMessage());
            // The lossless accessors still work.
            assertEquals(4294967295L, rs.getLong("c"));
        });
    }

    @Test
    @DisplayName("UInt64 is BigInteger, and getLong refuses values above Long.MAX_VALUE")
    void unsignedLong() throws SQLException {
        withRow(
                "SELECT toUInt64(18446744073709551615) AS big, toUInt64(42) AS small",
                (rs, meta) -> {
                    assertEquals("UInt64", meta.getColumnTypeName(1));
                    assertEquals(Types.NUMERIC, meta.getColumnType(1));
                    assertEquals(BigInteger.class.getName(), meta.getColumnClassName(1));

                    assertEquals(new BigInteger("18446744073709551615"), rs.getObject("big"));
                    assertEquals("18446744073709551615", rs.getString("big"));
                    assertEquals(new BigDecimal("18446744073709551615"), rs.getBigDecimal("big"));

                    SQLException e = assertThrows(SQLException.class, () -> rs.getLong("big"));
                    assertEquals("22003", e.getSQLState());

                    // A UInt64 that does fit reads as a long without complaint.
                    assertEquals(42L, rs.getLong("small"));
                });
    }

    @Test
    @DisplayName("floats keep their value, including NaN and both infinities")
    void floats() throws SQLException {
        withRow(
                "SELECT toFloat32(1.5) AS f, toFloat64(-2.25) AS d, nan AS n, inf AS pi, -inf AS ni",
                (rs, meta) -> {
                    assertEquals(Types.REAL, meta.getColumnType(1));
                    assertEquals(Types.DOUBLE, meta.getColumnType(2));
                    assertEquals(1.5f, rs.getFloat("f"));
                    assertEquals(-2.25d, rs.getDouble("d"));
                    assertTrue(Double.isNaN(rs.getDouble("n")));
                    assertEquals(Double.POSITIVE_INFINITY, rs.getDouble("pi"));
                    assertEquals(Double.NEGATIVE_INFINITY, rs.getDouble("ni"));
                    assertEquals(Float.valueOf(1.5f), rs.getObject("f"));
                });
    }

    @Test
    @DisplayName("Decimal keeps its scale exactly, with no float round trip")
    void decimals() throws SQLException {
        withRow(
                "SELECT toDecimal32(1.25, 2) AS d32, toDecimal64(123.456, 3) AS d64,"
                        + " toDecimal128(-0.000000001, 9) AS d128,"
                        + " toDecimal128('12345678901234567890.12', 2) AS wide",
                (rs, meta) -> {
                    // DECIMAL, not NUMERIC. Both are defensible for a fixed-point type; agreeing
                    // with clickhouse-jdbc is what decides it, since a column's reported type
                    // should not change under an application moving between the two drivers.
                    assertEquals(Types.DECIMAL, meta.getColumnType(1));
                    assertEquals(2, meta.getScale(1));
                    assertEquals(3, meta.getScale(2));

                    assertEquals(new BigDecimal("1.25"), rs.getBigDecimal("d32"));
                    assertEquals(new BigDecimal("123.456"), rs.getBigDecimal("d64"));
                    assertEquals(new BigDecimal("-0.000000001"), rs.getBigDecimal("d128"));
                    // Beyond double precision: the point of decoding decimals as integers.
                    assertEquals(new BigDecimal("12345678901234567890.12"), rs.getBigDecimal("wide"));
                    assertEquals("123.456", rs.getString("d64"));
                });
    }

    @Test
    @DisplayName("String is UTF-8 text, including astral characters")
    void strings() throws SQLException {
        withRow(
                "SELECT 'plain' AS a, 'héllo äø 你好 🎉' AS b, '' AS empty",
                (rs, meta) -> {
                    assertEquals("String", meta.getColumnTypeName(1));
                    assertEquals(Types.VARCHAR, meta.getColumnType(1));
                    assertTrue(meta.isCaseSensitive(1));
                    assertEquals("plain", rs.getString("a"));
                    assertEquals("héllo äø 你好 🎉", rs.getString("b"));
                    assertEquals("", rs.getString("empty"));
                    assertFalse(rs.wasNull(), "an empty string is not NULL");
                });
    }

    @Test
    @DisplayName("FixedString is bytes, readable losslessly with getBytes")
    void fixedString() throws SQLException {
        withRow("SELECT toFixedString('abc', 3) AS fs", (rs, meta) -> {
            assertEquals("FixedString(3)", meta.getColumnTypeName(1));
            // VARCHAR, not BINARY. The Arrow path saw a fixed-size binary column and could not
            // tell a FixedString from a UUID or an Int128 of the same width, so it reported
            // BINARY and rendered hex from getString. The engine's own type name says
            // FixedString, so it is text -- which is what clickhouse-jdbc reports too.
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertEquals(3, meta.getPrecision(1), "a FixedString's precision is its width");
            assertArrayEquals(new byte[] {'a', 'b', 'c'}, rs.getBytes("fs"));
            assertEquals("abc", rs.getString("fs"));
        });
    }

    @Test
    @DisplayName("Date maps to LocalDate, at both ends of its range")
    void dates() throws SQLException {
        withRow(
                "SELECT toDate('2026-09-08') AS d, toDate32('1900-01-01') AS old,"
                        + " toDate32('2200-12-31') AS future",
                (rs, meta) -> {
                    assertEquals(Types.DATE, meta.getColumnType(1));
                    // java.sql.Date, not LocalDate. JDBC 4.2's mapping table is explicit that
                    // the java.time types are what getObject(int, Class) is for; returning one
                    // from getObject(int) breaks framework code that switches on the returned
                    // type -- Spring's JdbcUtils.getResultSetValue, for one.
                    assertEquals(java.sql.Date.class.getName(), meta.getColumnClassName(1));
                    assertEquals(java.sql.Date.valueOf("2026-09-08"), rs.getObject("d"));
                    assertEquals("2026-09-08", rs.getDate("d").toString());
                    assertEquals(java.sql.Date.valueOf("1900-01-01"), rs.getObject("old"));
                    assertEquals(java.sql.Date.valueOf("2200-12-31"), rs.getObject("future"));
                    // The java.time form is still there, by asking for it.
                    assertEquals(LocalDate.of(2026, 9, 8), rs.getObject("d", LocalDate.class));
                    assertEquals("2026-09-08", rs.getString("d"));
                });
    }

    @Test
    @DisplayName("DateTime64 keeps sub-second precision and its timezone")
    void dateTimes() throws SQLException {
        withRow(
                "SELECT toDateTime64('2026-09-08 12:34:56.789', 3, 'UTC') AS ms,"
                        + " toDateTime64('2026-09-08 12:34:56.123456', 6, 'UTC') AS us,"
                        + " toDateTime64('1960-01-01 00:00:00.500', 3, 'UTC') AS preEpoch",
                (rs, meta) -> {
                    // TIMESTAMP and java.sql.Timestamp, as clickhouse-jdbc reports and as the
                    // specification's mapping table requires. The Arrow path said
                    // TIMESTAMP_WITH_TIMEZONE because the Arrow type carried a zone -- the
                    // engine's session zone, not anything this column declared.
                    assertEquals(Types.TIMESTAMP, meta.getColumnType(1));
                    assertEquals(java.sql.Timestamp.class.getName(), meta.getColumnClassName(1));
                    assertEquals(3, meta.getScale(1));
                    assertEquals(6, meta.getScale(2));

                    assertEquals(
                            java.sql.Timestamp.valueOf("2026-09-08 12:34:56.789"), rs.getObject("ms"));
                    assertEquals(
                            java.sql.Timestamp.valueOf("2026-09-08 12:34:56.123456"),
                            rs.getObject("us"));
                    // Pre-epoch is where floorDiv matters: truncating division would land this
                    // in the wrong second.
                    assertEquals(
                            java.sql.Timestamp.valueOf("1960-01-01 00:00:00.500"),
                            rs.getObject("preEpoch"));

                    assertEquals("2026-09-08 12:34:56.789", rs.getTimestamp("ms").toString());
                    // The instant is still available by asking, and computing it needs the
                    // column's zone -- which is why it is not what getObject returns.
                    assertEquals(
                            Instant.parse("2026-09-08T12:34:56.789Z"),
                            rs.getObject("ms", Instant.class));
                });
    }

    @Test
    @DisplayName("a timestamp is rendered in the Calendar's zone when one is given, else the column's")
    void timestampTimezone() throws SQLException {
        withRow("SELECT toDateTime64('2026-09-08 12:00:00', 0, 'UTC') AS t", (rs, meta) -> {
            // The column says UTC, so the zoneless Timestamp is UTC wall-clock.
            assertEquals("2026-09-08 12:00:00.0", rs.getTimestamp("t").toString());

            java.util.Calendar tokyo =
                    java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
            // The Calendar overload does not move it. Those overloads exist to interpret a
            // value whose zone is unknown; this column's zone is declared, so shifting by the
            // caller's calendar would move a moment that was never ambiguous.
            assertEquals("2026-09-08 12:00:00.0", rs.getTimestamp("t", tokyo).toString());

            // The instant is available by asking, and is computed from the column's zone.
            assertEquals(Instant.parse("2026-09-08T12:00:00Z"), rs.getObject("t", Instant.class));
        });
    }

    @Test
    @DisplayName("UUID maps to java.util.UUID")
    void uuids() throws SQLException {
        withRow(
                "SELECT toUUID('550e8400-e29b-41d4-a716-446655440000') AS u",
                (rs, meta) -> {
                    assertEquals("UUID", meta.getColumnTypeName(1));
                    assertEquals(UUID.class.getName(), meta.getColumnClassName(1));
                    assertEquals(
                            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), rs.getObject("u"));
                    assertEquals("550e8400-e29b-41d4-a716-446655440000", rs.getString("u"));
                    assertEquals(
                            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                            rs.getObject("u", UUID.class));
                });
    }

    @Test
    @DisplayName("Nullable reports NULL through every accessor, and wasNull tracks the last read")
    void nulls() throws SQLException {
        withRow(
                "SELECT CAST(NULL AS Nullable(Int32)) AS ni, CAST(NULL AS Nullable(String)) AS ns,"
                        + " CAST(NULL AS Nullable(DateTime64(3))) AS nt, toInt32(7) AS present",
                (rs, meta) -> {
                    assertEquals(ResultSetMetaData.columnNullable, meta.isNullable(1));
                    assertEquals(ResultSetMetaData.columnNoNulls, meta.isNullable(4));

                    assertNull(rs.getObject("ni"));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getString("ns"));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getTimestamp("nt"));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getBigDecimal("ni"));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getBytes("ns"));
                    assertTrue(rs.wasNull());

                    // Primitive accessors return the zero value and set wasNull.
                    assertEquals(0, rs.getInt("ni"));
                    assertTrue(rs.wasNull());
                    assertEquals(0.0, rs.getDouble("ni"));
                    assertTrue(rs.wasNull());
                    assertFalse(rs.getBoolean("ni"));
                    assertTrue(rs.wasNull());

                    // And a real value clears it again.
                    assertEquals(7, rs.getInt("present"));
                    assertFalse(rs.wasNull());
                });
    }

    @Test
    @DisplayName("Nullable columns holding values read as those values")
    void nullableWithValues() throws SQLException {
        withRow(
                "SELECT CAST(5 AS Nullable(Int32)) AS a, CAST('x' AS Nullable(String)) AS b",
                (rs, meta) -> {
                    assertEquals(5, rs.getInt("a"));
                    assertFalse(rs.wasNull());
                    assertEquals("x", rs.getString("b"));
                    assertFalse(rs.wasNull());
                });
    }

    @Test
    @DisplayName("Enum arrives as its underlying integer, and reads as text through toString")
    void enums() throws SQLException {
        withRow(
                "SELECT CAST('b' AS Enum8('a' = 1, 'b' = 2)) AS e,"
                        + " toString(CAST('b' AS Enum8('a' = 1, 'b' = 2))) AS s",
                (rs, meta) -> {
                    // The engine's Arrow converter materializes an Enum to its integer, so that
                    // is what the driver sees -- there is no Arrow enum type to preserve.
                    assertEquals(2, rs.getInt("e"));
                    assertEquals("b", rs.getString("s"));
                });
    }

    @Test
    @DisplayName("LowCardinality is materialized to its base type, not left dictionary-encoded")
    void lowCardinality() throws SQLException {
        // Only over String: ClickHouse refuses LowCardinality over a fixed-width type unless
        // allow_suspicious_low_cardinality_types is set, so String is the case that occurs.
        withRow("SELECT CAST('x' AS LowCardinality(String)) AS lc", (rs, meta) -> {
            // The wrapper survives now. The Arrow path erased it -- a LowCardinality(String)
            // and a String were both reported as "String" -- because Arrow carries dictionary
            // encoding as a property of the array rather than of the type. The engine's own
            // type name keeps it, and so does clickhouse-jdbc.
            assertEquals("LowCardinality(String)", meta.getColumnTypeName(1));
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertEquals("x", rs.getString("lc"));
        });
    }

    @Test
    @DisplayName("composites read as themselves, and a type with no reader is a typed error")
    void compositesReadAndTheRestIsATypedError() throws SQLException {
        // These used to be refused. The Arrow path had no flat mapping for them and reported
        // "Unsupported(arrow=+l)" as the column's type name, which is a diagnostic string in
        // the place a GUI puts a type. Reading RowBinary, they have their real names and their
        // values decode.
        withRow("SELECT [1, 2, 3] AS a, map('a', 1) AS m, tuple(1, 'x') AS t", (rs, meta) -> {
            assertEquals("Array(UInt8)", meta.getColumnTypeName(1));
            assertEquals(Types.ARRAY, meta.getColumnType(1));
            // A java.sql.Array, which is what getColumnClassName promises and what a framework
            // reaches for. Returning the bare Object[] would make the driver disagree with its
            // own metadata.
            java.sql.Array array = (java.sql.Array) rs.getObject("a");
            assertArrayEquals(new Object[] {(short) 1, (short) 2, (short) 3}, (Object[]) array.getArray());
            assertEquals("[1, 2, 3]", rs.getString("a"));

            assertEquals("Map(String, UInt8)", meta.getColumnTypeName(2));
            assertEquals(java.util.Map.of("a", (short) 1), rs.getObject("m"));

            assertEquals("Tuple(UInt8, String)", meta.getColumnTypeName(3));
            Object[] tuple = (Object[]) rs.getObject("t");
            assertEquals((short) 1, tuple[0]);
            assertEquals("x", tuple[1]);
        });

        // What is still unreadable: an AggregateFunction state is an opaque per-function blob
        // with no documented layout. The requirement is unchanged -- a typed error naming the
        // column, never a wrong value -- only the set of types it applies to has shrunk.
        try (Connection connection = openMemory();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT quantileState(0.5)(number) AS s FROM numbers(10)")) {
            assertEquals(
                    "AggregateFunction(quantile(0.5), UInt64)", rs.getMetaData().getColumnTypeName(1));
            // The refusal lands on next() rather than on the accessor, and it has to: a
            // row-wise format gives no way to skip a value whose length the decoder cannot
            // work out, so the row is unreadable and not just the column.
            SQLException e = assertThrows(SQLException.class, rs::next);
            assertEquals("0A000", e.getSQLState());
            assertTrue(e.getMessage().contains("AggregateFunction"), e.getMessage());
            assertTrue(e.getMessage().contains("toString("), "no workaround offered: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("an unsupported type read as text works, which is the documented workaround")
    void unsupportedTypeAsText() throws SQLException {
        withRow("SELECT toString([1, 2, 3]) AS arr, toString(map('a', 1)) AS m", (rs, meta) -> {
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertEquals("[1,2,3]", rs.getString("arr"));
            assertEquals("{'a':1}", rs.getString("m"));
        });
    }
}
