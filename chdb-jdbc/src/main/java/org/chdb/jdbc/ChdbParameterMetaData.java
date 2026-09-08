package org.chdb.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * How many parameters a {@link ChdbPreparedStatement} has, and nothing more.
 *
 * <p>chDB's C ABI has no way to describe a statement without running it, so the per-parameter
 * type a caller might hope for is not knowable: the driver would have to execute the query to
 * find out, which is not something a metadata call may do.
 *
 * <p>{@link #getParameterCount()} is exact -- {@link SqlParameterLexer} counted the
 * placeholders. The type accessors report {@code VARCHAR}/{@code String}, which is what the
 * driver actually binds: every placeholder is declared {@code :String} and the engine converts
 * from text in the surrounding expression. So this is the truth about the binding, not a
 * placeholder answer, even though it says nothing about the column being compared against.
 */
final class ChdbParameterMetaData implements ParameterMetaData {

    private final int count;

    ChdbParameterMetaData(int count) {
        this.count = count;
    }

    private void check(int param) throws SQLException {
        if (param < 1 || param > count) {
            throw new SQLException(
                    "Parameter index " + param + " is out of range; this statement has " + count
                            + " parameter(s), indexed from 1.",
                    "07009");
        }
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        // setNull() is accepted for every parameter, and nothing here can prove a target
        // column rejects NULL.
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.VARCHAR;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return "String";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return String.class.getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
        return parameterModeIn;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName(), "0A000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
