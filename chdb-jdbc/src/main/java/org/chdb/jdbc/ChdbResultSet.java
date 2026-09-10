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
import org.chdb.internal.ArrowBatch;
import org.chdb.internal.ArrowFieldType;
import org.chdb.internal.ArrowSchemaView;
import org.chdb.internal.ChdbNative;
import org.chdb.internal.ChdbNativeException;

/**
 * A forward-only cursor over an Arrow stream, holding one batch at a time.
 *
 * <h2>Memory</h2>
 * Exactly one batch is live. {@link #next()} releases the current batch before fetching the
 * next, so peak memory tracks the batch size rather than the size of the whole result (work
 * plan section 3.3). A 100 GB result reads in the same footprint as a 100 MB one.
 *
 * <h2>Batch release ordering</h2>
 * The batch's buffers are direct views onto native memory. Before asking the shim to release
 * a batch, {@link ArrowBatch#invalidate()} drops every Java reference to those buffers; a
 * read that arrives afterwards gets an exception instead of reading freed memory. Getting
 * this order wrong is a JVM crash, not a bug report, so it lives in one place: {@link
 * #releaseBatch()}.
 */
public final class ChdbResultSet implements ResultSet {

    private final ChdbStatement statement;
    private final long stream;
    private final ArrowSchemaView schema;
    private final long maxRows;

    private ArrowBatch batch;
    private long rowInBatch = -1;
    private long rowsReturned;
    private boolean exhausted;

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

    ChdbResultSet(ChdbStatement statement, long stream, long maxRows) throws SQLException {
        this.statement = statement;
        this.stream = stream;
        this.maxRows = maxRows;
        try {
            this.schema =
                    new ArrowSchemaView(
                            ChdbNative.streamColumnNames(stream),
                            ChdbNative.streamColumnFormats(stream),
                            ChdbNative.streamColumnNullable(stream));
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Failed to read the result schema", e);
        }
    }

    // ------------------------------------------------------------------ cursor

    @Override
    public boolean next() throws SQLException {
        checkOpen();

        if (maxRows > 0 && rowsReturned >= maxRows) {
            // setMaxRows is a cap on rows delivered, and there is no reason to keep the
            // engine producing rows nobody will read.
            exhaustAndRelease();
            return false;
        }

        if (batch != null && rowInBatch + 1 < batch.rowCount()) {
            rowInBatch++;
            rowsReturned++;
            return true;
        }

        if (exhausted) {
            return false;
        }

        // A zero-row batch is legitimate -- the engine can emit one before it has rows --
        // and must not be mistaken for end of stream, hence the loop.
        while (true) {
            long rows = advance();
            if (rows < 0) {
                exhausted = true;
                statement.stopTimeout();
                statement.clearInFlight(stream);
                return false;
            }
            if (rows > 0) {
                rowInBatch = 0;
                rowsReturned++;
                return true;
            }
        }
    }

    /** Releases the current batch and makes the next one current. Returns rows, or -1 at end. */
    private long advance() throws SQLException {
        releaseBatch();
        long rows;
        try {
            rows = ChdbNative.streamAdvance(statement.connection.handle(), stream);
        } catch (ChdbNativeException e) {
            throw cancellationAware("Failed to read the next batch", e);
        }
        if (rows < 0) {
            return -1;
        }

        Object[] columns;
        try {
            columns = ChdbNative.streamBatchColumns(stream);
        } catch (ChdbNativeException e) {
            throw ChdbExceptions.wrap("Failed to map the Arrow batch", e);
        }
        batch = new ArrowBatch(columns, schema.types(), rows);
        rowInBatch = -1;
        return rows;
    }

    /**
     * Drops the Java view of the current batch, then asks the shim to free it.
     *
     * <p>That order is the whole safety property: after {@code invalidate()} no accessor can
     * reach the native memory, so freeing it cannot be observed.
     */
    private void releaseBatch() {
        ArrowBatch current = batch;
        batch = null;
        rowInBatch = -1;
        if (current != null) {
            current.invalidate();
        }
    }

    private void exhaustAndRelease() throws SQLException {
        exhausted = true;
        statement.stopTimeout();
        // Cancel rather than draining: the caller has what it asked for, and letting the
        // engine finish a query nobody is reading wastes exactly as much work as it has left.
        try {
            ChdbNative.streamCancel(statement.connection.handle(), stream);
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
     * pool being closed does to a connection it has lent out. What came back before was the
     * shim's account of the handles involved ("stream handle 18801 does not belong to connection
     * handle 18576"), which describes an ownership mix-up that did not happen and says nothing
     * a caller can act on. Both are checked before the generic path so the answer names the
     * close.
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

    private ArrowBatch currentBatch() throws SQLException {
        checkOpen();
        if (batch == null || rowInBatch < 0) {
            throw new SQLException(
                    "No current row. Call next() and check that it returned true before reading a"
                            + " column.",
                    "24000");
        }
        return batch;
    }

    /** Translates a 1-based JDBC column index into a 0-based one, or fails precisely. */
    private int columnIndex(int jdbcIndex) throws SQLException {
        if (jdbcIndex < 1 || jdbcIndex > schema.columnCount()) {
            throw new SQLDataException(
                    "Column index "
                            + jdbcIndex
                            + " is out of range. This result set has "
                            + schema.columnCount()
                            + " column(s), indexed from 1.",
                    "22023");
        }
        return jdbcIndex - 1;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastValueWasNull;
    }

    /** Every accessor funnels through here, so {@code wasNull()} has exactly one writer. */
    private boolean readNull(int column) throws SQLException {
        lastValueWasNull = currentBatch().isNull(column, (int) rowInBatch);
        return lastValueWasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        return decodeString(column);
    }

    /**
     * A column's value as text.
     *
     * <p>Every supported type has a string form, because {@code getString} is what frameworks
     * fall back to and returning null for a readable value would look like a NULL. The forms
     * are the ones round-tripping through ClickHouse SQL: ISO-8601 for temporals, plain
     * decimal for numbers, lowercase hex for binary.
     */
    private String decodeString(int column) throws SQLException {
        ArrowFieldType type = schema.typeRef(column);
        try {
            switch (type.kind()) {
                case BOOL:
                    return batch.getBoolean(column, (int) rowInBatch) ? "true" : "false";
                case UTF8:
                case LARGE_UTF8:
                    return batch.getString(column, (int) rowInBatch);
                case BINARY:
                case LARGE_BINARY:
                    return toHex(batch.getBytes(column, (int) rowInBatch));
                case FIXED_SIZE_BINARY:
                    return type.byteWidth() == 16
                            ? batch.getUuid(column, (int) rowInBatch).toString()
                            : toHex(batch.getBytes(column, (int) rowInBatch));
                case DECIMAL:
                    return batch.getBigDecimal(column, (int) rowInBatch).toPlainString();
                case UINT64:
                    return batch.getBigInteger(column, (int) rowInBatch).toString();
                case FLOAT16:
                case FLOAT32:
                    return Float.toString((float) batch.getDouble(column, (int) rowInBatch));
                case FLOAT64:
                    return Double.toString(batch.getDouble(column, (int) rowInBatch));
                case DATE32:
                case DATE64:
                    return batch.getLocalDate(column, (int) rowInBatch).toString();
                case TIMESTAMP:
                    return renderTimestamp(column, type);
                case TIME32:
                case TIME64:
                    return batch.getLocalTime(column, (int) rowInBatch).toString();
                case UNSUPPORTED:
                    throw unsupportedColumn(column, type);
                default:
                    return Long.toString(batch.getLong(column, (int) rowInBatch));
            }
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    private String renderTimestamp(int column, ArrowFieldType type) {
        Instant instant = batch.getInstant(column, (int) rowInBatch);
        ZoneId zone = type.timezone() == null ? ZoneOffset.UTC : ZoneId.of(type.timezone());
        return instant.atZone(zone).toLocalDateTime().toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return false;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            switch (type.kind()) {
                case BOOL:
                    return batch.getBoolean(column, (int) rowInBatch);
                case UTF8:
                case LARGE_UTF8:
                {
                    String value = batch.getString(column, (int) rowInBatch).trim();
                    return "true".equalsIgnoreCase(value) || "1".equals(value) || "t".equalsIgnoreCase(value);
                }
                case FLOAT16:
                case FLOAT32:
                case FLOAT64:
                    return batch.getDouble(column, (int) rowInBatch) != 0.0;
                case DECIMAL:
                    return batch.getBigDecimal(column, (int) rowInBatch).signum() != 0;
                case UINT64:
                    return batch.getBigInteger(column, (int) rowInBatch).signum() != 0;
                case UNSUPPORTED:
                    throw unsupportedColumn(column, type);
                default:
                    // JDBC's rule for numeric-to-boolean: zero is false, anything else true.
                    return batch.getLong(column, (int) rowInBatch) != 0;
            }
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) narrow(columnIndex, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) narrow(columnIndex, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) narrow(columnIndex, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    /**
     * Reads an integral value and refuses to truncate it.
     *
     * <p>A UInt32 of 4e9 does fit a {@code long} and does not fit an {@code int}. Silently
     * wrapping it to a negative number is the kind of data corruption that shows up as a
     * business bug months later, so the read fails and names the value and the accessor that
     * would hold it.
     */
    private long narrow(int columnIndex, long min, long max, String javaType) throws SQLException {
        long value = getLong(columnIndex);
        if (lastValueWasNull) {
            return 0;
        }
        if (value < min || value > max) {
            throw new SQLDataException(
                    "Value "
                            + value
                            + " in column "
                            + columnIndex
                            + " ("
                            + schema.columnName(columnIndex - 1)
                            + ", "
                            + schema.typeRef(columnIndex - 1).typeName()
                            + ") does not fit a Java "
                            + javaType
                            + ". Read it with getLong(), getBigDecimal() or getObject().",
                    "22003");
        }
        return value;
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return 0;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            switch (type.kind()) {
                case BOOL:
                    return batch.getBoolean(column, (int) rowInBatch) ? 1 : 0;
                case FLOAT16:
                case FLOAT32:
                case FLOAT64:
                    return (long) batch.getDouble(column, (int) rowInBatch);
                case DECIMAL:
                    return batch.getBigDecimal(column, (int) rowInBatch)
                            .setScale(0, RoundingMode.DOWN)
                            .longValueExact();
                case UTF8:
                case LARGE_UTF8:
                    return Long.parseLong(batch.getString(column, (int) rowInBatch).trim());
                case UNSUPPORTED:
                    throw unsupportedColumn(column, type);
                default:
                    return batch.getLong(column, (int) rowInBatch);
            }
        } catch (ArithmeticException e) {
            throw new SQLDataException(
                    "Value in column " + columnIndex + " (" + schema.columnName(column) + ", "
                            + type.typeName() + ") does not fit a Java long: " + e.getMessage()
                            + ". Read it with getBigDecimal() or getObject().",
                    "22003",
                    e);
        } catch (NumberFormatException e) {
            throw new SQLDataException(
                    "Column " + columnIndex + " (" + schema.columnName(column)
                            + ") holds text that is not an integer: " + e.getMessage(),
                    "22018",
                    e);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return 0.0;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            switch (type.kind()) {
                case FLOAT16:
                case FLOAT32:
                case FLOAT64:
                    return batch.getDouble(column, (int) rowInBatch);
                case DECIMAL:
                case UINT64:
                    return batch.getBigDecimal(column, (int) rowInBatch).doubleValue();
                case BOOL:
                    return batch.getBoolean(column, (int) rowInBatch) ? 1.0 : 0.0;
                case UTF8:
                case LARGE_UTF8:
                    return Double.parseDouble(batch.getString(column, (int) rowInBatch).trim());
                case UNSUPPORTED:
                    throw unsupportedColumn(column, type);
                default:
                    return batch.getLong(column, (int) rowInBatch);
            }
        } catch (NumberFormatException e) {
            throw new SQLDataException(
                    "Column " + columnIndex + " (" + schema.columnName(column)
                            + ") holds text that is not a number: " + e.getMessage(),
                    "22018",
                    e);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            if (type.kind() == ArrowFieldType.Kind.UTF8 || type.kind() == ArrowFieldType.Kind.LARGE_UTF8) {
                return new BigDecimal(batch.getString(column, (int) rowInBatch).trim());
            }
            if (type.kind() == ArrowFieldType.Kind.UNSUPPORTED) {
                throw unsupportedColumn(column, type);
            }
            return batch.getBigDecimal(column, (int) rowInBatch);
        } catch (NumberFormatException e) {
            throw new SQLDataException(
                    "Column " + columnIndex + " (" + schema.columnName(column)
                            + ") holds text that is not a decimal: " + e.getMessage(),
                    "22018",
                    e);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            if (type.kind() == ArrowFieldType.Kind.UNSUPPORTED) {
                throw unsupportedColumn(column, type);
            }
            switch (type.kind()) {
                case BINARY:
                case LARGE_BINARY:
                case FIXED_SIZE_BINARY:
                case UTF8:
                case LARGE_UTF8:
                    return batch.getBytes(column, (int) rowInBatch);
                default:
                    // Any other type read as bytes is its text form's UTF-8, which is what
                    // ClickHouse's own binary formats would produce for it.
                    return decodeString(column).getBytes(StandardCharsets.UTF_8);
            }
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        LocalDate date = getLocalDate(columnIndex);
        return date == null ? null : Date.valueOf(date);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        LocalDate date = getLocalDate(columnIndex);
        if (date == null) {
            return null;
        }
        if (cal == null) {
            return Date.valueOf(date);
        }
        // JDBC's Calendar overloads mean "interpret the value in this calendar's zone".
        Calendar copy = (Calendar) cal.clone();
        copy.clear();
        copy.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        return new Date(copy.getTimeInMillis());
    }

    private LocalDate getLocalDate(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            if (type.kind() == ArrowFieldType.Kind.UTF8 || type.kind() == ArrowFieldType.Kind.LARGE_UTF8) {
                return LocalDate.parse(batch.getString(column, (int) rowInBatch).trim());
            }
            return batch.getLocalDate(column, (int) rowInBatch);
        } catch (java.time.format.DateTimeParseException e) {
            throw new SQLDataException(
                    "Column " + columnIndex + " (" + schema.columnName(column)
                            + ") holds text that is not an ISO-8601 date: " + e.getMessage(),
                    "22007",
                    e);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        LocalTime time = getLocalTime(columnIndex);
        return time == null ? null : Time.valueOf(time);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        LocalTime time = getLocalTime(columnIndex);
        if (time == null) {
            return null;
        }
        if (cal == null) {
            return Time.valueOf(time);
        }
        Calendar copy = (Calendar) cal.clone();
        copy.clear();
        copy.set(1970, Calendar.JANUARY, 1, time.getHour(), time.getMinute(), time.getSecond());
        return new Time(copy.getTimeInMillis());
    }

    private LocalTime getLocalTime(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            return batch.getLocalTime(column, (int) rowInBatch);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getTimestamp(columnIndex, null);
    }

    /**
     * A timestamp in a specific zone.
     *
     * <p>{@link Timestamp} is a zoneless wall-clock type, so producing one requires choosing a
     * zone. The rules, in order:
     *
     * <ol>
     *   <li>the {@link Calendar}'s zone, when one is passed;
     *   <li>the zone the engine tagged the column with, for a {@code DateTime64(p, 'tz')};
     *   <li>UTC.
     * </ol>
     *
     * UTC rather than the JVM default is the important choice: a default-zone fallback makes
     * the same query return different instants on a developer laptop and a UTC server, which
     * is exactly the bug this driver should not ship.
     */
    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            if (type.kind() == ArrowFieldType.Kind.UTF8 || type.kind() == ArrowFieldType.Kind.LARGE_UTF8) {
                return Timestamp.valueOf(
                        LocalDateTime.parse(batch.getString(column, (int) rowInBatch).trim()));
            }
            Instant instant = batch.getInstant(column, (int) rowInBatch);
            ZoneId zone;
            if (cal != null) {
                zone = cal.getTimeZone().toZoneId();
            } else if (type.timezone() != null) {
                zone = ZoneId.of(type.timezone());
            } else {
                zone = ZoneOffset.UTC;
            }
            return Timestamp.valueOf(LocalDateTime.ofInstant(instant, zone));
        } catch (java.time.format.DateTimeParseException e) {
            throw new SQLDataException(
                    "Column " + columnIndex + " (" + schema.columnName(column)
                            + ") holds text that is not an ISO-8601 timestamp: " + e.getMessage(),
                    "22007",
                    e);
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }
        ArrowFieldType type = schema.typeRef(column);
        try {
            switch (type.kind()) {
                case BOOL:
                    return batch.getBoolean(column, (int) rowInBatch);
                case INT8:
                    return (byte) batch.getLong(column, (int) rowInBatch);
                case UINT8:
                case INT16:
                    return (short) batch.getLong(column, (int) rowInBatch);
                case UINT16:
                case INT32:
                    return (int) batch.getLong(column, (int) rowInBatch);
                case UINT32:
                case INT64:
                case DURATION:
                    return batch.getLong(column, (int) rowInBatch);
                case UINT64:
                    return batch.getBigInteger(column, (int) rowInBatch);
                case DECIMAL:
                    return batch.getBigDecimal(column, (int) rowInBatch);
                case FLOAT16:
                case FLOAT32:
                    return (float) batch.getDouble(column, (int) rowInBatch);
                case FLOAT64:
                    return batch.getDouble(column, (int) rowInBatch);
                case UTF8:
                case LARGE_UTF8:
                    return batch.getString(column, (int) rowInBatch);
                case BINARY:
                case LARGE_BINARY:
                    return batch.getBytes(column, (int) rowInBatch);
                case FIXED_SIZE_BINARY:
                    return type.byteWidth() == 16
                            ? batch.getUuid(column, (int) rowInBatch)
                            : batch.getBytes(column, (int) rowInBatch);
                case DATE32:
                case DATE64:
                    return batch.getLocalDate(column, (int) rowInBatch);
                case TIMESTAMP:
                    return batch.getInstant(column, (int) rowInBatch);
                case TIME32:
                case TIME64:
                    return batch.getLocalTime(column, (int) rowInBatch);
                case UNSUPPORTED:
                default:
                    throw unsupportedColumn(column, type);
            }
        } catch (IllegalStateException e) {
            throw decodeFailure(column, type, e);
        }
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> targetType) throws SQLException {
        if (targetType == null) {
            throw new SQLDataException("target type must not be null", "22023");
        }
        int column = columnIndex(columnIndex);
        if (readNull(column)) {
            return null;
        }

        Object value;
        if (targetType == String.class) {
            value = getString(columnIndex);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            value = getBoolean(columnIndex);
        } else if (targetType == Byte.class || targetType == byte.class) {
            value = getByte(columnIndex);
        } else if (targetType == Short.class || targetType == short.class) {
            value = getShort(columnIndex);
        } else if (targetType == Integer.class || targetType == int.class) {
            value = getInt(columnIndex);
        } else if (targetType == Long.class || targetType == long.class) {
            value = getLong(columnIndex);
        } else if (targetType == Float.class || targetType == float.class) {
            value = getFloat(columnIndex);
        } else if (targetType == Double.class || targetType == double.class) {
            value = getDouble(columnIndex);
        } else if (targetType == BigDecimal.class) {
            value = getBigDecimal(columnIndex);
        } else if (targetType == BigInteger.class) {
            BigDecimal decimal = getBigDecimal(columnIndex);
            value = decimal == null ? null : decimal.toBigIntegerExact();
        } else if (targetType == byte[].class) {
            value = getBytes(columnIndex);
        } else if (targetType == LocalDate.class) {
            value = getLocalDate(columnIndex);
        } else if (targetType == LocalTime.class) {
            value = getLocalTime(columnIndex);
        } else if (targetType == Instant.class) {
            value = batch.getInstant(column, (int) rowInBatch);
        } else if (targetType == LocalDateTime.class) {
            ArrowFieldType type = schema.typeRef(column);
            ZoneId zone = type.timezone() == null ? ZoneOffset.UTC : ZoneId.of(type.timezone());
            value = LocalDateTime.ofInstant(batch.getInstant(column, (int) rowInBatch), zone);
        } else if (targetType == Date.class) {
            value = getDate(columnIndex);
        } else if (targetType == Time.class) {
            value = getTime(columnIndex);
        } else if (targetType == Timestamp.class) {
            value = getTimestamp(columnIndex);
        } else if (targetType == UUID.class) {
            value = batch.getUuid(column, (int) rowInBatch);
        } else if (targetType == Object.class) {
            value = getObject(columnIndex);
        } else {
            throw ChdbExceptions.notSupported(
                    "Converting column " + columnIndex + " (" + schema.typeRef(column).typeName()
                            + ") to " + targetType.getName());
        }
        return targetType.cast(value);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw ChdbExceptions.notSupported("Custom type maps in getObject");
        }
        return getObject(columnIndex);
    }

    private SQLException unsupportedColumn(int column, ArrowFieldType type) {
        return new SQLFeatureNotSupportedException(
                "Column "
                        + (column + 1)
                        + " ("
                        + schema.columnName(column)
                        + ") has type "
                        + type.typeName()
                        + ", which the chDB JDBC driver V1 cannot read. V1 covers the scalar type"
                        + " matrix in docs/type-mapping.md; Array, Map, Tuple, Nested, Variant, JSON"
                        + " and Dynamic are not in it. Cast the column in SQL -- for example"
                        + " toString("
                        + schema.columnName(column)
                        + ") -- to read it as text.",
                "0A000");
    }

    /**
     * Wraps a decoding failure from {@link ArrowBatch}.
     *
     * <p>{@code IllegalStateException} from the batch means either a released batch or Arrow
     * buffers that do not match their schema. Both are driver or engine bugs rather than user
     * errors, so the message says so and asks for a report.
     */
    private SQLException decodeFailure(int column, ArrowFieldType type, IllegalStateException cause) {
        return new SQLException(
                "Failed to decode column "
                        + (column + 1)
                        + " ("
                        + schema.columnName(column)
                        + ", "
                        + type.typeName()
                        + ", arrow format \""
                        + type.format()
                        + "\"): "
                        + cause.getMessage()
                        + ". This is a driver or engine defect rather than a problem with your"
                        + " query; please report it with the query and the schema.",
                "HY000",
                cause);
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
            for (int i = schema.columnCount() - 1; i >= 0; i--) {
                // Built backwards so that on a duplicate label the lowest index wins, which is
                // what JDBC requires.
                index.put(schema.columnName(i).toLowerCase(Locale.ROOT), i + 1);
            }
            nameIndex = index;
        }
        Integer found = nameIndex.get(columnLabel.toLowerCase(Locale.ROOT));
        if (found == null) {
            throw new SQLSyntaxErrorException(
                    "No column named \""
                            + columnLabel
                            + "\" in this result set. Available: "
                            + String.join(", ", schema.columnNames())
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
        return new ChdbResultSetMetaData(schema);
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
        // Answering would need a one-row lookahead, which for a stream means fetching a batch
        // the caller may never read. Refusing is honest; guessing would be wrong at a batch
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
     * Releases the current batch and the engine's stream immediately.
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

        // Order matters: drop the Java view of the batch before the shim frees it.
        releaseBatch();

        SQLException failure = null;
        if (!exhausted) {
            // Tell the engine to stop producing rows nobody will read.
            try {
                ChdbNative.streamCancel(statement.connection.handle(), stream);
            } catch (ChdbNativeException ignored) {
                // Cancelling a stream that already finished is a no-op, and a failure here
                // must not stop the close below.
            }
        }

        // Deregistered before the handle is destroyed. A cancel() on another thread reads this
        // registration, so the other order leaves a window in which it is handed an id that
        // streamClose() has already removed, and hands the caller a native failure for a
        // cancel that simply lost its race. See ChdbStatement.cancel().
        statement.clearInFlight(stream);

        try {
            ChdbNative.streamClose(stream);
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
                        + " they stream one batch at a time; buffering the whole result to allow"
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
        throw ChdbExceptions.notSupported(
                "Array (V1 does not map ClickHouse Array columns; read them as text with"
                        + " toString(col))");
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
