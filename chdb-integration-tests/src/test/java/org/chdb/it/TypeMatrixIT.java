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
                    assertEquals(Types.NUMERIC, meta.getColumnType(1));
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
            assertEquals(Types.BINARY, meta.getColumnType(1));
            assertArrayEquals(new byte[] {'a', 'b', 'c'}, rs.getBytes("fs"));
            assertEquals("616263", rs.getString("fs"), "text form of a binary column is hex");
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
                    assertEquals(LocalDate.class.getName(), meta.getColumnClassName(1));
                    assertEquals(LocalDate.of(2026, 9, 8), rs.getObject("d"));
                    assertEquals("2026-09-08", rs.getDate("d").toString());
                    assertEquals(LocalDate.of(1900, 1, 1), rs.getObject("old"));
                    assertEquals(LocalDate.of(2200, 12, 31), rs.getObject("future"));
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
                    assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, meta.getColumnType(1));
                    assertEquals(Instant.class.getName(), meta.getColumnClassName(1));

                    assertEquals(Instant.parse("2026-09-08T12:34:56.789Z"), rs.getObject("ms"));
                    assertEquals(Instant.parse("2026-09-08T12:34:56.123456Z"), rs.getObject("us"));
                    // Pre-epoch is where floorDiv matters: truncating division would land this
                    // in the wrong second.
                    assertEquals(Instant.parse("1960-01-01T00:00:00.500Z"), rs.getObject("preEpoch"));

                    assertEquals("2026-09-08 12:34:56.789", rs.getTimestamp("ms").toString());
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
            assertEquals("2026-09-08 21:00:00.0", rs.getTimestamp("t", tokyo).toString());

            // The instant itself is zone-independent, which is why getObject returns one.
            assertEquals(Instant.parse("2026-09-08T12:00:00Z"), rs.getObject("t"));
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
            // Materialized, so the driver sees Arrow utf8 and never has to decode a dictionary
            // -- which is what chdb_arrow_options.low_cardinality_as_dictionary=0 buys.
            assertEquals("String", meta.getColumnTypeName(1));
            assertEquals(Types.VARCHAR, meta.getColumnType(1));
            assertEquals("x", rs.getString("lc"));
        });
    }

    @Test
    @DisplayName("an unreadable type is a typed error naming the column, never a wrong value")
    void unsupportedTypesAreRefusedPrecisely() throws SQLException {
        // Every one of these has no flat Arrow mapping the driver can decode. What matters is
        // that reading it raises SQLFeatureNotSupportedException rather than mis-slicing
        // buffers, and that the message says which column and how to work around it.
        String[] expressions = {
            "[1, 2, 3]", "map('a', 1)", "tuple(1, 'x')",
        };
        for (String expression : expressions) {
            try (Connection connection = openMemory();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT " + expression + " AS c")) {
                ResultSetMetaData meta = rs.getMetaData();
                assertTrue(
                        meta.getColumnTypeName(1).startsWith("Unsupported("),
                        expression + " reported as " + meta.getColumnTypeName(1));
                assertEquals(Types.OTHER, meta.getColumnType(1));
                assertTrue(rs.next());

                SQLFeatureNotSupportedException e =
                        assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getObject(1));
                assertTrue(e.getMessage().contains("Column 1"), e.getMessage());
                assertTrue(e.getMessage().contains("toString("), "no workaround offered: " + e.getMessage());
            } catch (SQLException e) {
                // Some of these the engine refuses to stream at all, which is also an acceptable
                // outcome: it is a clear error rather than a wrong value.
                assertTrue(
                        e.getMessage().contains("UNKNOWN_TYPE") || e.getMessage().contains("Unsupported"),
                        expression + " -> " + e.getMessage());
            }
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
