package org.chdb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * A prepared statement whose parameters are bound by the engine, never spliced into SQL.
 *
 * <p>{@link SqlParameterLexer} scans the statement once, at construction, and each execution
 * assembles it with a {@code {pN:String}} reference where every {@code ?} was; the values go
 * alongside through {@code chdb_query_with_params_n}. So no value a caller can set changes the
 * shape of the statement (work plan section 5.8) -- the engine has finished parsing before it
 * ever sees one.
 *
 * <h2>How values are formatted</h2>
 * ClickHouse parses a bound parameter's text according to the type in its placeholder, which
 * is {@code String} (or {@code Nullable(String)} for a parameter bound to NULL). So each
 * setter produces the text whose String-to-target conversion in the surrounding SQL expression
 * yields the intended value: a plain decimal for numbers, {@code YYYY-MM-DD} for dates,
 * {@code YYYY-MM-DD HH:MM:SS[.fff]} for timestamps, {@code 1}/{@code 0} for booleans -- the
 * same forms ClickHouse itself accepts in a literal.
 *
 * <p>That text is then escaped by {@link TextEscape}, because the engine reads a bound value
 * with {@code deserializeTextEscaped} rather than taking it verbatim. Without that step a
 * value holding a backslash arrives altered and one holding a newline fails the query
 * outright. The escaping is a wire encoding for the value channel and not SQL quoting -- the
 * value never enters the statement text.
 *
 * <h2>Timezone</h2>
 * {@link #setTimestamp(int, Timestamp)} and friends render in UTC unless given a {@link
 * Calendar}. A JVM-default-zone rendering would make the same code write different instants on
 * a developer's laptop and a UTC server, which is not a behaviour to ship.
 */
public final class ChdbPreparedStatement extends ChdbStatement implements PreparedStatement {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    private static final DateTimeFormatter TIMESTAMP_FORMAT_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String originalSql;
    private final SqlParameterLexer lexer;

    /**
     * Bound values in the engine's escaped-text form, indexed from 0 for JDBC parameter 1.
     * Holds {@link TextEscape#NULL_MARKER} for a parameter bound to SQL NULL.
     */
    private final String[] values;

    /** Whether each parameter has been set. Distinct from being set to NULL. */
    private final boolean[] bound;

    /**
     * Whether each parameter is bound to SQL NULL, which decides the type its placeholder is
     * declared with for this execution.
     */
    private final boolean[] isNull;

    ChdbPreparedStatement(ChdbConnection connection, String sql) throws SQLException {
        super(connection);
        this.originalSql = sql;
        this.lexer = SqlParameterLexer.parse(sql);
        this.values = new String[lexer.parameterCount()];
        this.bound = new boolean[lexer.parameterCount()];
        this.isNull = new boolean[lexer.parameterCount()];
    }

    // ------------------------------------------------------------------ execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!executeBound(Boolean.TRUE)) {
            throw new SQLException(
                    "executeQuery() requires a statement that returns a result set, but this one"
                            + " does not: "
                            + abbreviate(originalSql),
                    "07500");
        }
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        long count = executeLargeUpdate();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        if (executeBound(Boolean.FALSE)) {
            throw new SQLException(
                    "executeUpdate() requires a statement that does not return a result set, but"
                            + " this one does: "
                            + abbreviate(originalSql),
                    "07500");
        }
        return getLargeUpdateCount();
    }

    @Override
    public boolean execute() throws SQLException {
        return executeBound(null);
    }

    private boolean executeBound(Boolean expectResultSet) throws SQLException {
        checkOpen();
        List<String> unbound = new ArrayList<>();
        for (int i = 0; i < bound.length; i++) {
            if (!bound[i]) {
                unbound.add(Integer.toString(i + 1));
            }
        }
        if (!unbound.isEmpty()) {
            throw new SQLException(
                    "Parameter"
                            + (unbound.size() == 1 ? " " : "s ")
                            + String.join(", ", unbound)
                            + " of "
                            + bound.length
                            + " "
                            + (unbound.size() == 1 ? "was" : "were")
                            + " never set. Every ? must be bound before execution; use setNull() to"
                            + " bind a SQL NULL.",
                    "07002");
        }

        // The placeholder types depend on which parameters are NULL, which is only settled
        // now, so the statement text is assembled per execution.
        List<String> types = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            // Only a nullable placeholder makes the engine read \N as NULL; under :String it
            // would arrive as the single character N.
            types.add(isNull[i] ? "Nullable(String)" : "String");
        }

        return executeInternal(
                lexer.render(types), lexer.parameterNames(), Arrays.asList(values), expectResultSet);
    }

    /**
     * Parameters survive execution, so re-executing with the same values works.
     *
     * <p>JDBC leaves this to the driver, and keeping them is the more useful reading: a caller
     * that wants them gone calls {@link #clearParameters()}. What must not survive is anything
     * engine-side, and nothing does -- {@code chdb_query_with_params_n} scopes its bindings to
     * the single call and clears them on return.
     */
    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(values, null);
        Arrays.fill(bound, false);
        Arrays.fill(isNull, false);
    }

    private static String abbreviate(String sql) {
        String collapsed = sql.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 120 ? collapsed : collapsed.substring(0, 117) + "...";
    }

    // ------------------------------------------------------------------ binding

    /**
     * Records one parameter's value, escaping it for the engine's escaped-text reader.
     *
     * <p>Every setter goes through here, so no setter can forget the escaping -- which is the
     * point of funnelling them: the failure it prevents is silent for backslashes and a hard
     * error for newlines.
     */
    private void bind(int parameterIndex, String value) throws SQLException {
        bindRaw(parameterIndex, TextEscape.escape(value), false);
    }

    /** Records an already-encoded value. Only NULL uses this; everything else is escaped. */
    private void bindRaw(int parameterIndex, String encoded, boolean nullValue) throws SQLException {
        checkOpen();
        if (parameterIndex < 1 || parameterIndex > values.length) {
            throw new SQLException(
                    "Parameter index "
                            + parameterIndex
                            + " is out of range. This statement has "
                            + values.length
                            + " parameter(s), indexed from 1: "
                            + abbreviate(originalSql),
                    "07009");
        }
        values[parameterIndex - 1] = encoded;
        bound[parameterIndex - 1] = true;
        isNull[parameterIndex - 1] = nullValue;
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        // \N is the escaped-text NULL marker, and marking the parameter null is what makes
        // its placeholder Nullable(String) for this execution so the engine reads it as NULL.
        // The declared sqlType is not needed: the placeholder carries the type, which is why
        // every setNull overload behaves identically.
        bindRaw(parameterIndex, TextEscape.NULL_MARKER, true);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        bind(parameterIndex, x ? "1" : "0");
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        bind(parameterIndex, Byte.toString(x));
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        bind(parameterIndex, Short.toString(x));
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        bind(parameterIndex, Integer.toString(x));
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        bind(parameterIndex, Long.toString(x));
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        if (Float.isNaN(x) || Float.isInfinite(x)) {
            bind(parameterIndex, specialDouble(x));
            return;
        }
        bind(parameterIndex, Float.toString(x));
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            bind(parameterIndex, specialDouble(x));
            return;
        }
        bind(parameterIndex, Double.toString(x));
    }

    /**
     * ClickHouse spells the non-finite floats {@code nan}, {@code inf} and {@code -inf}. Java's
     * {@code NaN} and {@code Infinity} are not accepted, so they are translated rather than
     * passed through to fail as a parse error.
     */
    private static String specialDouble(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        return value > 0 ? "inf" : "-inf";
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.NUMERIC);
            return;
        }
        // toPlainString, not toString: the latter can emit scientific notation, which
        // ClickHouse's Decimal parser does not accept.
        bind(parameterIndex, x.toPlainString());
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.VARCHAR);
            return;
        }
        // Verbatim, with no quoting or escaping. The value never enters the SQL text, so a
        // quote, a backslash, a newline or a NUL in it is just data -- the length-carrying
        // _n entry point preserves all of them.
        bind(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.VARBINARY);
            return;
        }
        // Bound as text, so binary has to be encoded. Hex with an unhex() around the
        // placeholder is the documented pattern; raw bytes would be reinterpreted as UTF-8.
        StringBuilder hex = new StringBuilder(x.length * 2);
        for (byte b : x) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        bind(parameterIndex, hex.toString());
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.DATE);
            return;
        }
        bind(parameterIndex, x.toLocalDate().toString());
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        if (x == null || cal == null) {
            setDate(parameterIndex, x);
            return;
        }
        Calendar copy = (Calendar) cal.clone();
        copy.setTimeInMillis(x.getTime());
        bind(
                parameterIndex,
                String.format(
                        "%04d-%02d-%02d",
                        copy.get(Calendar.YEAR), copy.get(Calendar.MONTH) + 1, copy.get(Calendar.DAY_OF_MONTH)));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.TIME);
            return;
        }
        bind(parameterIndex, x.toLocalTime().toString());
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        if (x == null || cal == null) {
            setTime(parameterIndex, x);
            return;
        }
        Calendar copy = (Calendar) cal.clone();
        copy.setTimeInMillis(x.getTime());
        bind(
                parameterIndex,
                String.format(
                        "%02d:%02d:%02d",
                        copy.get(Calendar.HOUR_OF_DAY), copy.get(Calendar.MINUTE), copy.get(Calendar.SECOND)));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.TIMESTAMP);
            return;
        }
        bind(parameterIndex, format(x.toLocalDateTime()));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.TIMESTAMP);
            return;
        }
        if (cal == null) {
            setTimestamp(parameterIndex, x);
            return;
        }
        LocalDateTime moment =
                LocalDateTime.ofInstant(x.toInstant(), cal.getTimeZone().toZoneId());
        bind(parameterIndex, format(moment));
    }

    /** Renders without a trailing run of zero sub-second digits, which ClickHouse also accepts. */
    private static String format(LocalDateTime moment) {
        if (moment.getNano() == 0) {
            return TIMESTAMP_FORMAT_SECONDS.format(moment);
        }
        return TIMESTAMP_FORMAT.format(moment);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        } else if (x instanceof String) {
            setString(parameterIndex, (String) x);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else if (x instanceof Byte) {
            setByte(parameterIndex, (Byte) x);
        } else if (x instanceof Short) {
            setShort(parameterIndex, (Short) x);
        } else if (x instanceof Integer) {
            setInt(parameterIndex, (Integer) x);
        } else if (x instanceof Long) {
            setLong(parameterIndex, (Long) x);
        } else if (x instanceof Float) {
            setFloat(parameterIndex, (Float) x);
        } else if (x instanceof Double) {
            setDouble(parameterIndex, (Double) x);
        } else if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        } else if (x instanceof BigInteger) {
            bind(parameterIndex, x.toString());
        } else if (x instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) x);
        } else if (x instanceof Date) {
            setDate(parameterIndex, (Date) x);
        } else if (x instanceof Time) {
            setTime(parameterIndex, (Time) x);
        } else if (x instanceof Timestamp) {
            setTimestamp(parameterIndex, (Timestamp) x);
        } else if (x instanceof LocalDate) {
            bind(parameterIndex, x.toString());
        } else if (x instanceof LocalTime) {
            bind(parameterIndex, x.toString());
        } else if (x instanceof LocalDateTime) {
            bind(parameterIndex, format((LocalDateTime) x));
        } else if (x instanceof Instant) {
            bind(parameterIndex, format(LocalDateTime.ofInstant((Instant) x, ZoneOffset.UTC)));
        } else if (x instanceof OffsetDateTime) {
            bind(
                    parameterIndex,
                    format(((OffsetDateTime) x).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()));
        } else if (x instanceof UUID) {
            bind(parameterIndex, x.toString());
        } else if (x instanceof Character) {
            setString(parameterIndex, x.toString());
        } else {
            throw ChdbExceptions.notSupported(
                    "Binding a "
                            + x.getClass().getName()
                            + " as parameter "
                            + parameterIndex
                            + ". Convert it to a supported type first: see docs/type-mapping.md for"
                            + " the list");
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        // The target type is not used: the placeholder is always :String and the engine
        // converts from text in the surrounding expression, so the Java type is what decides
        // the rendering.
        setObject(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
            throws SQLException {
        if (x instanceof BigDecimal && scaleOrLength >= 0) {
            setBigDecimal(
                    parameterIndex,
                    ((BigDecimal) x).setScale(scaleOrLength, java.math.RoundingMode.HALF_UP));
            return;
        }
        setObject(parameterIndex, x);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.VARCHAR);
            return;
        }
        setString(parameterIndex, x.toString());
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setString(parameterIndex, readFully(reader, length));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        if (length > Integer.MAX_VALUE) {
            throw ChdbExceptions.notSupported("Character streams longer than Integer.MAX_VALUE");
        }
        setString(parameterIndex, readFully(reader, (int) length));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setString(parameterIndex, readFully(reader, -1));
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setCharacterStream(parameterIndex, value);
    }

    private String readFully(Reader reader, int length) throws SQLException {
        if (reader == null) {
            return null;
        }
        // A bound parameter is a value, not a stream: it has to be materialized before it can
        // be sent. That is a real limit rather than an oversight -- the C ABI takes a byte
        // range, so there is nothing to stream into.
        StringBuilder out = new StringBuilder(length > 0 ? length : 256);
        char[] buffer = new char[4096];
        try {
            int read;
            while ((read = reader.read(buffer)) > 0) {
                out.append(buffer, 0, read);
                if (length > 0 && out.length() >= length) {
                    break;
                }
            }
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to read the parameter's character stream: " + e, "22000", e);
        }
        if (length > 0 && out.length() > length) {
            out.setLength(length);
        }
        return out.toString();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBytes(parameterIndex, readFully(x, length));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        if (length > Integer.MAX_VALUE) {
            throw ChdbExceptions.notSupported("Streams longer than Integer.MAX_VALUE");
        }
        setBytes(parameterIndex, readFully(x, (int) length));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        byte[] bytes = readFully(x, -1);
        setString(parameterIndex, bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBytes(parameterIndex, readFully(x, length));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        if (length > Integer.MAX_VALUE) {
            throw ChdbExceptions.notSupported("Streams longer than Integer.MAX_VALUE");
        }
        setBytes(parameterIndex, readFully(x, (int) length));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setBytes(parameterIndex, readFully(x, -1));
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw ChdbExceptions.notSupported("setUnicodeStream (deprecated since JDBC 2.0)");
    }

    private byte[] readFully(InputStream in, int length) throws SQLException {
        if (in == null) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(length > 0 ? length : 256);
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (length > 0 && out.size() >= length) {
                    break;
                }
            }
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to read the parameter's stream: " + e, "22000", e);
        }
        byte[] bytes = out.toByteArray();
        if (length > 0 && bytes.length > length) {
            bytes = Arrays.copyOf(bytes, length);
        }
        return bytes;
    }

    // ------------------------------------------------------------------ metadata

    /**
     * Column metadata before execution.
     *
     * <p>Not available: chDB's C ABI has no "describe this statement" call, and the schema of
     * an Arrow stream only exists once the engine has produced a batch. Executing the
     * statement to find out would run the caller's query as a side effect of asking about it.
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        ResultSet current = getResultSet();
        if (current != null) {
            return current.getMetaData();
        }
        throw ChdbExceptions.notSupported(
                "getMetaData() before execution (chDB cannot describe a statement without running"
                        + " it; call it on the ResultSet after executeQuery())");
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        return new ChdbParameterMetaData(lexer.parameterCount());
    }

    // ------------------------------------------------------------------ unsupported types

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw ChdbExceptions.notSupported("Ref parameters");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw ChdbExceptions.notSupported("Blob parameters (use setBytes)");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw ChdbExceptions.notSupported("Blob parameters (use setBytes)");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw ChdbExceptions.notSupported("Blob parameters (use setBytes)");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw ChdbExceptions.notSupported("Clob parameters (use setString)");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw ChdbExceptions.notSupported("Clob parameters (use setString)");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw ChdbExceptions.notSupported("Clob parameters (use setString)");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw ChdbExceptions.notSupported("NClob parameters (use setString)");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw ChdbExceptions.notSupported("NClob parameters (use setString)");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw ChdbExceptions.notSupported("NClob parameters (use setString)");
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw ChdbExceptions.notSupported(
                "Array parameters (build the array in SQL, for example"
                        + " arrayMap(...) or splitByChar(',', ?))");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw ChdbExceptions.notSupported("RowId parameters");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw ChdbExceptions.notSupported("SQLXML parameters");
    }

    @Override
    public void addBatch() throws SQLException {
        throw ChdbExceptions.notSupported("Batch updates");
    }
}
