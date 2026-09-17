package org.chdb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.chdb.internal.ClickHouseType;
import org.chdb.internal.JdbcValues;
import org.chdb.internal.RowBinaryCursor;
import org.chdb.internal.RowBinaryDecoder;
import org.chdb.internal.RowBinaryHeader;
import org.chdb.internal.ChdbNative;
import org.chdb.internal.ChdbNativeException;

/**
 * A forward-only cursor over a {@code RowBinaryWithNamesAndTypes} stream.
 *
 * <h2>Memory</h2>
 * One chunk is live at a time. The cursor drops a chunk when it moves to the next, so peak
 * memory tracks a chunk rather than the whole result: a 100 GB result reads in the same
 * footprint as a 100 MB one.
 *
 * <h2>What changed, and why there is less to get wrong here now</h2>
 * The Arrow path this replaces handed out direct buffers onto engine memory, so every accessor
 * had to be sequenced against the release of the batch behind it — read after release was a JVM
 * crash rather than an exception, and the ordering lived in one carefully commented place.
 * RowBinary chunks are copied out of the engine before Java sees them, so that hazard does not
 * exist: there is no native memory for a Java reference to outlive.
 *
 * <p>The types are the engine's own, from the stream's header, rather than the Arrow schema's
 * lossy projection of them. That is the point of the change: {@code Enum8} arrives with its
 * labels, {@code IPv6} does not arrive indistinguishable from a {@code UUID}, and
 * {@code DateTime} is a timestamp rather than a {@code UInt32}.
 */
public final class ChdbResultSet implements ResultSet {

    private final ChdbStatement statement;
    private final long stream;
    private final RowBinaryCursor cursor;
    private final RowBinaryHeader header;
    private final long maxRows;

    private long rowsReturned;
    private boolean exhausted;
    private boolean positioned;

    /**
     * Volatile because a result set can be closed by a thread other than the one reading it:
     * {@link ChdbConnection#close()} closes its statements, and JDBC allows {@code
     * Connection.abort()} from another thread outright. A reader that does not see this flag
     * proceeds into the shim with a stream whose connection is going away, which is a native
     * error message where "the result set is closed" is the answer.
     */
    private volatile boolean closed;

    private boolean lastValueWasNull;

    /** Lazily built lowercase name -> 1-based index, for {@link #findColumn(String)}. */
    private Map<String, Integer> nameIndex;

    ChdbResultSet(ChdbStatement statement, long stream, RowBinaryCursor cursor, long maxRows) {
        this.statement = statement;
        this.stream = stream;
        this.cursor = cursor;
        this.header = cursor.header();
        this.maxRows = maxRows;
    }

    // ------------------------------------------------------------------ cursor

    @Override
    public boolean next() throws SQLException {
        checkOpen();

        if (maxRows > 0 && rowsReturned >= maxRows) {
            // setMaxRows is a cap on rows delivered, and there is no reason to keep the
            // engine producing rows nobody will read.
            exhaustAndRelease();
            positioned = false;
            return false;
        }
        if (exhausted) {
            positioned = false;
            return false;
        }

        boolean advanced;
        try {
            advanced = cursor.next();
        } catch (ChdbNativeException e) {
            throw cancellationAware("Failed to read the next chunk", e);
        } catch (RowBinaryDecoder.UnsupportedTypeException e) {
            // A column this driver cannot decode. It fails the row rather than only that
            // column, and there is no way round that: the decoder does not know how many bytes
            // the value occupies, so it cannot skip it to reach the next column. Naming the
            // column and the type is what is left to do.
            throw new SQLFeatureNotSupportedException(
                    "Row " + (rowsReturned + 1) + " has a column of type " + e.type().name()
                            + ", which this driver cannot decode. Cast it in the query --"
                            + " toString(col) always works -- or select the columns you need.",
                    "0A000",
                    0,
                    e);
        } catch (RuntimeException e) {
            // A backstop, deliberately broad. Everything above this is checked or converted,
            // and an unchecked exception out of next() escapes every caller's
            // catch(SQLException) -- which is the defect this whole change set has been
            // removing, and which I reintroduced here once by catching only
            // IllegalStateException.
            throw new SQLDataException(
                    "The result stream could not be read: " + e, "22000", 0, e);
        }
        if (!advanced) {
            exhausted = true;
            positioned = false;
            statement.stopTimeout();
            statement.clearInFlight(stream);
            // A cancelled stream stops producing and its next fetch is simply empty, which is
            // indistinguishable from a natural end at this level -- so the reason has to be
            // asked for. Reporting a truncated result as a complete one is worse than the
            // exception: the caller would believe they had read everything.
            if (statement.timedOut()) {
                throw new SQLTimeoutException(
                        "The query exceeded the statement's query timeout and was cancelled, so"
                                + " the result is incomplete",
                        "57014",
                        159);
            }
            if (statement.wasCancelled()) {
                throw new SQLException(
                        "The statement was cancelled, so the result is incomplete", "57014", 394);
            }
            return false;
        }
        rowsReturned++;
        positioned = true;
        return true;
    }

    private void exhaustAndRelease() throws SQLException {
        exhausted = true;
        statement.stopTimeout();
        // Cancel rather than draining: the caller has what it asked for, and letting the
        // engine finish a query nobody is reading wastes exactly as much work as it has left.
        try {
            cursor.cancel();
        } catch (ChdbNativeException ignored) {
            // The stream may have finished on its own; there is nothing to recover.
        }
        statement.clearInFlight(stream);
    }

    /**
     * Reports a mid-stream failure as a timeout, a cancellation or a close when that is what it
     * was.
     *
     * <p>The engine's message for a cancelled query says the query was cancelled, which is
     * accurate but hides the cause the caller cares about: whether their own {@code cancel()}
     * or their {@code setQueryTimeout} ended it.
     *
     * <p>The two close cases are here for the same reason and are not hypothetical. This result
     * set, or the connection under it, can be closed by another thread while this one is in
     * {@code next()} — which is what {@code Connection.abort()} is defined to do, and what a
     * pool being closed does to a connection it has lent out.
     */
    private SQLException cancellationAware(String context, ChdbNativeException cause) {
        if (closed) {
            return new SQLNonTransientException(
                    context + ": the ResultSet was closed while it was being read", "HY010", 0, cause);
        }
        if (statement.connection.isClosed()) {
            return new SQLNonTransientConnectionException(
                    context
                            + ": the Connection this ResultSet was opened on was closed while it"
                            + " was being read, so the rest of the result is gone."
                            + " Connection.abort(), and a connection pool shutting down while it"
                            + " has this connection lent out, both do this.",
                    "08003",
                    0,
                    cause);
        }
        if (statement.timedOut()) {
            return new SQLTimeoutException(
                    context + ": the query exceeded the statement's query timeout and was cancelled",
                    "57014",
                    ChdbExceptions.errorCode(cause.getMessage()),
                    cause);
        }
        if (statement.wasCancelled()) {
            return new SQLException(context + ": the statement was cancelled", "57014", 394, cause);
        }
        return ChdbExceptions.wrap(context, cause);
    }

    /** Translates a 1-based JDBC column index into a 0-based one, or fails precisely. */
    private int columnIndex(int jdbcIndex) throws SQLException {
        if (jdbcIndex < 1 || jdbcIndex > header.columnCount()) {
            throw new SQLDataException(
                    "Column index "
                            + jdbcIndex
                            + " is out of range. This result set has "
                            + header.columnCount()
                            + " column(s), indexed from 1.",
                    "22023");
        }
        return jdbcIndex - 1;
    }

    /**
     * The decoded value of a column of the current row, and the single writer of
     * {@code wasNull()}.
     *
     * <p>Every accessor comes through here, which is what keeps {@code wasNull} honest: it
     * describes the last column actually read and nothing else.
     */
    private Object read(int jdbcIndex) throws SQLException {
        checkOpen();
        if (!positioned) {
            throw new SQLException(
                    "No current row. Call next() and check that it returned true before reading a"
                            + " column.",
                    "24000");
        }
        Object value = cursor.value(columnIndex(jdbcIndex));
        lastValueWasNull = value == null;
        return value;
    }

    /** The column descriptor a conversion failure names. */
    private JdbcValues.Column describe(int jdbcIndex) throws SQLException {
        int column = columnIndex(jdbcIndex);
        return new JdbcValues.Column(jdbcIndex, header.names().get(column), header.types().get(column));
    }

    private ClickHouseType typeOf(int jdbcIndex) throws SQLException {
        return header.types().get(columnIndex(jdbcIndex));
    }

    // ------------------------------------------------------------------ reading

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastValueWasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return JdbcValues.asString(describe(columnIndex), read(columnIndex));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return JdbcValues.asBoolean(describe(columnIndex), read(columnIndex));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return JdbcValues.asByte(describe(columnIndex), read(columnIndex));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return JdbcValues.asShort(describe(columnIndex), read(columnIndex));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return JdbcValues.asInt(describe(columnIndex), read(columnIndex));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return JdbcValues.asLong(describe(columnIndex), read(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return JdbcValues.asFloat(describe(columnIndex), read(columnIndex));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return JdbcValues.asDouble(describe(columnIndex), read(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return JdbcValues.asBigDecimal(describe(columnIndex), read(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        // Deprecated since JDBC 2.0 but still called by older frameworks. HALF_UP rather than
        // the default, because a caller asking for a scale wants a rounded number, not an
        // ArithmeticException.
        return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return JdbcValues.asBytes(describe(columnIndex), read(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return JdbcValues.asDate(describe(columnIndex), read(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        // The Calendar overloads exist to reinterpret a wall-clock value in another zone. The
        // engine's value is already a wall clock in the column's zone, so shifting it again
        // would move a moment that was never ambiguous; the calendar is accepted and ignored,
        // which is what the value's own timezone makes correct. docs/type-mapping.md says so.
        return getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return JdbcValues.asTime(describe(columnIndex), read(columnIndex));
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return JdbcValues.asTimestamp(describe(columnIndex), read(columnIndex));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return JdbcValues.asObject(describe(columnIndex), read(columnIndex));
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        // The map is for user-defined types, which ClickHouse does not have.
        return getObject(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> targetType) throws SQLException {
        if (targetType == null) {
            throw new SQLDataException("getObject requires a target type, not null", "22023");
        }
        JdbcValues.Column column = describe(columnIndex);
        Object raw = read(columnIndex);
        if (raw == null) {
            return null;
        }
        Object converted = convert(column, raw, targetType);
        if (converted == null) {
            throw new SQLDataException(
                    column.describe() + " cannot be read as " + targetType.getName(), "22000");
        }
        return targetType.cast(converted);
    }

    /**
     * The typed {@code getObject} conversions.
     *
     * <p>Every branch goes through {@link JdbcValues}, so a type asked for by class and the same
     * type asked for by accessor cannot disagree -- which is the defect this replaced: the old
     * path reached past its own conversions for three of these and let an
     * {@code IllegalStateException} out of a JDBC accessor.
     */
    private Object convert(JdbcValues.Column column, Object raw, Class<?> targetType)
            throws SQLException {
        if (targetType == String.class) {
            return JdbcValues.asString(column, raw);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return JdbcValues.asBoolean(column, raw);
        }
        if (targetType == Byte.class || targetType == byte.class) {
            return JdbcValues.asByte(column, raw);
        }
        if (targetType == Short.class || targetType == short.class) {
            return JdbcValues.asShort(column, raw);
        }
        if (targetType == Integer.class || targetType == int.class) {
            return JdbcValues.asInt(column, raw);
        }
        if (targetType == Long.class || targetType == long.class) {
            return JdbcValues.asLong(column, raw);
        }
        if (targetType == Float.class || targetType == float.class) {
            return JdbcValues.asFloat(column, raw);
        }
        if (targetType == Double.class || targetType == double.class) {
            return JdbcValues.asDouble(column, raw);
        }
        if (targetType == BigDecimal.class) {
            return JdbcValues.asBigDecimal(column, raw);
        }
        if (targetType == java.math.BigInteger.class) {
            BigDecimal value = JdbcValues.asBigDecimal(column, raw);
            return value == null ? null : value.toBigIntegerExact();
        }
        if (targetType == byte[].class) {
            return JdbcValues.asBytes(column, raw);
        }
        if (targetType == Date.class) {
            return JdbcValues.asDate(column, raw);
        }
        if (targetType == Time.class) {
            return JdbcValues.asTime(column, raw);
        }
        if (targetType == Timestamp.class) {
            return JdbcValues.asTimestamp(column, raw);
        }
        if (targetType == java.time.LocalDate.class) {
            Date date = JdbcValues.asDate(column, raw);
            return date == null ? null : date.toLocalDate();
        }
        if (targetType == java.time.LocalTime.class) {
            // From the decoded value rather than from java.sql.Time, which has no sub-second
            // field and would silently drop a Time64's milliseconds.
            return raw instanceof java.time.LocalTime
                    ? raw
                    : raw instanceof java.time.LocalDateTime
                            ? ((java.time.LocalDateTime) raw).toLocalTime()
                            : null;
        }
        if (targetType == java.time.LocalDateTime.class) {
            return raw instanceof java.time.LocalDateTime
                    ? raw
                    : raw instanceof java.time.LocalDate
                            ? ((java.time.LocalDate) raw).atStartOfDay()
                            : null;
        }
        if (targetType == java.time.Instant.class || targetType == java.time.OffsetDateTime.class) {
            // The only place the column's zone is needed: the decoded value is a wall clock,
            // and turning it back into a moment requires knowing which zone it was read in.
            java.time.LocalDateTime local =
                    raw instanceof java.time.LocalDateTime
                            ? (java.time.LocalDateTime) raw
                            : raw instanceof java.time.LocalDate
                                    ? ((java.time.LocalDate) raw).atStartOfDay()
                                    : null;
            if (local == null) {
                return null;
            }
            java.time.ZoneId zone =
                    column.type().unwrapped().timeZone() != null
                            ? java.time.ZoneId.of(column.type().unwrapped().timeZone())
                            : cursor.options().sessionTimeZone();
            java.time.ZonedDateTime zoned = local.atZone(zone);
            return targetType == java.time.Instant.class ? zoned.toInstant() : zoned.toOffsetDateTime();
        }
        if (targetType == UUID.class) {
            return raw instanceof UUID ? raw : null;
        }
        if (targetType == java.net.InetAddress.class) {
            return raw instanceof java.net.InetAddress ? raw : null;
        }
        if (targetType == java.sql.Array.class || targetType == Map.class) {
            Object value = JdbcValues.asObject(column, raw);
            return targetType.isInstance(value) ? value : null;
        }
        if (targetType == Object.class) {
            return JdbcValues.asObject(column, raw);
        }
        // A class we have no rule for, but which the value already is.
        Object value = JdbcValues.asObject(column, raw);
        return value != null && targetType.isInstance(value) ? value : null;
    }


    // ------------------------------------------------------------------ by column name

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel == null) {
            throw new SQLDataException("column label must not be null", "22023");
        }
        if (nameIndex == null) {
            Map<String, Integer> index = new HashMap<>();
            for (int i = header.columnCount() - 1; i >= 0; i--) {
                // Built backwards so that on a duplicate label the lowest index wins, which is
                // what JDBC requires.
                index.put(header.names().get(i).toLowerCase(Locale.ROOT), i + 1);
            }
            nameIndex = index;
        }
        Integer found = nameIndex.get(columnLabel.toLowerCase(Locale.ROOT));
        if (found == null) {
            throw new SQLSyntaxErrorException(
                    "No column named \""
                            + columnLabel
                            + "\" in this result set. Available: "
                            + String.join(", ", header.names())
                            + ".",
                    "42S22");
        }
        return found;
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    // ------------------------------------------------------------------ streams

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] bytes = getBytes(columnIndex);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null
                ? null
                : new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("getUnicodeStream (deprecated since JDBC 2.0)");
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("getUnicodeStream (deprecated since JDBC 2.0)");
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new StringReader(value);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(columnLabel);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getString(columnLabel);
    }

    // ------------------------------------------------------------------ metadata and position

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new ChdbResultSetMetaData(header);
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw ChdbExceptions.notSupported("Fetch direction " + direction);
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return statement.getFetchSize();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        statement.setFetchSize(rows);
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        // Rows delivered so far. A forward-only cursor over a stream has no other meaningful
        // row number, and this is the one JDBC defines for it.
        return rowsReturned > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rowsReturned;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return rowsReturned == 0 && !exhausted;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return rowsReturned == 1;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return exhausted && rowsReturned > 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        // Answering would need a one-row lookahead, which for a stream means fetching a chunk
        // the caller may never read. Refusing is honest; guessing would be wrong at a chunk
        // boundary.
        throw ChdbExceptions.notSupported("isLast() on a forward-only streaming result set");
    }

    @Override
    public String getCursorName() throws SQLException {
        throw ChdbExceptions.notSupported("Named cursors");
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Releases the current chunk and the engine's stream immediately.
     *
     * <p>Not deferred to a {@code Cleaner}: an unread stream holds engine-side memory and,
     * until it is cancelled, keeps the query running. Closing early -- reading one row of a
     * billion and stopping -- is a supported and cheap thing to do.
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;

        SQLException failure = null;
        if (!exhausted) {
            // Tell the engine to stop producing rows nobody will read, before the handle goes.
            try {
                cursor.cancel();
            } catch (ChdbNativeException ignored) {
                // Cancelling a stream that already finished is a no-op, and a failure here
                // must not stop the close below.
            }
        }

        // Deregistered before the handle is destroyed. A cancel() on another thread reads this
        // registration, so the other order leaves a window in which it is handed an id the
        // close has already removed, and hands the caller a native failure for a cancel that
        // simply lost its race. See ChdbStatement.cancel().
        statement.clearInFlight(stream);

        try {
            // No ordering hazard beyond that one: chunks are copied out of the engine before
            // Java sees them, so releasing the stream cannot be observed by a reader. The Arrow
            // path this replaces had to sequence every accessor against this call.
            cursor.close();
        } catch (ChdbNativeException e) {
            failure = ChdbExceptions.wrap("Failed to close the result set", e);
        }

        statement.resultSetClosed(this);

        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw ChdbExceptions.closed("ResultSet");
        }
    }

    // ------------------------------------------------------------------ scrolling: not in V1

    @Override
    public boolean previous() throws SQLException {
        throw scrollingNotSupported("previous()");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw scrollingNotSupported("absolute()");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw scrollingNotSupported("relative()");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw scrollingNotSupported("beforeFirst()");
    }

    @Override
    public void afterLast() throws SQLException {
        throw scrollingNotSupported("afterLast()");
    }

    @Override
    public boolean first() throws SQLException {
        throw scrollingNotSupported("first()");
    }

    @Override
    public boolean last() throws SQLException {
        throw scrollingNotSupported("last()");
    }

    private SQLFeatureNotSupportedException scrollingNotSupported(String method) {
        return new SQLFeatureNotSupportedException(
                method
                        + " needs a scrollable result set. chDB result sets are forward-only because"
                        + " they stream one chunk at a time; buffering the whole result to allow"
                        + " scrolling would defeat that. Collect the rows you need into a Java"
                        + " collection, or re-run the query with an ORDER BY and LIMIT.",
                "0A000");
    }

    // ------------------------------------------------------------------ updates: read-only

    @Override
    public boolean rowUpdated() throws SQLException {
        throw readOnly();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        throw readOnly();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    private SQLFeatureNotSupportedException readOnly() {
        return new SQLFeatureNotSupportedException(
                "chDB result sets are CONCUR_READ_ONLY. Modify data with an INSERT, ALTER TABLE"
                        + " UPDATE or ALTER TABLE DELETE statement instead.",
                "0A000");
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    // ------------------------------------------------------------------ SQL types not in V1

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("Ref");
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("Ref");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("Blob (read binary columns with getBytes)");
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("Blob (read binary columns with getBytes)");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("Clob (read text columns with getString)");
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("Clob (read text columns with getString)");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("NClob (read text columns with getString)");
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("NClob (read text columns with getString)");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        // The same object getObject() hands back for an Array column, so the two cannot
        // disagree. Refusing here while getObject() returned a java.sql.Array was the state
        // this left behind when Array columns became readable.
        Object value = getObject(columnIndex);
        if (value == null || value instanceof Array) {
            return (Array) value;
        }
        throw ChdbExceptions.notSupported(
                "getArray on a column of type " + describe(columnIndex).type().name()
                        + " (only Array columns have one; read this one with getObject or"
                        + " getString)");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("SQLXML");
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("SQLXML");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("RowId (chDB tables have no row identity)");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("RowId (chDB tables have no row identity)");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw ChdbExceptions.notSupported("getURL (read the column with getString)");
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        throw ChdbExceptions.notSupported("getURL (read the column with getString)");
    }

    // ------------------------------------------------------------------ wrapper

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
