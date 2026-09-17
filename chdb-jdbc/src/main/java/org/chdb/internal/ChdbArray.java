package org.chdb.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;

/**
 * A {@code java.sql.Array} over a decoded ClickHouse array.
 *
 * <p>Exists because {@code getObject} on an {@code ARRAY} column is specified to return one of
 * these, and because {@link JdbcTypeMapping#className} says it does. Returning the bare
 * {@code Object[]} instead -- which the old path did -- makes the driver disagree with its own
 * metadata, and breaks every framework that reaches for {@code getArray}.
 *
 * <p>Immutable and already materialised: the elements were decoded before this was built, so
 * {@link #free()} has nothing to release and the object stays usable after it, which is more
 * useful than the specification's minimum.
 */
public final class ChdbArray implements Array {

    private final ClickHouseType elementType;
    private final Object[] elements;

    ChdbArray(ClickHouseType elementType, Object[] elements) {
        this.elementType = elementType;
        this.elements = elements;
    }

    @Override
    public String getBaseTypeName() {
        return elementType.name();
    }

    @Override
    public int getBaseType() {
        return JdbcTypeMapping.jdbcType(elementType);
    }

    @Override
    public Object getArray() {
        return elements;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) {
        return elements;
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        // One-based, as everywhere else in JDBC.
        if (index < 1 || count < 0 || index - 1 + count > elements.length) {
            throw new SQLException(
                    "slice [" + index + ", " + count + ") is outside an array of "
                            + elements.length + " element(s)", "22003");
        }
        Object[] out = new Object[count];
        System.arraycopy(elements, (int) (index - 1), out, 0, count);
        return out;
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        throw unsupported();
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        throw unsupported();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        throw unsupported();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map)
            throws SQLException {
        throw unsupported();
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException(
                "Array.getResultSet() is not implemented; use getArray(), which returns the"
                        + " elements already decoded", "0A000");
    }

    @Override
    public void free() {
        // Nothing to release: the elements are ordinary Java objects, not a cursor over engine
        // memory. Deliberately leaves the array usable rather than poisoning it.
    }

    @Override
    public String toString() {
        return JdbcValues.renderArray(elementType, elements);
    }
}
