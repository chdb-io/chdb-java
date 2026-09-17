package org.chdb.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.chdb.internal.ClickHouseType;
import org.chdb.internal.JdbcTypeMapping;
import org.chdb.internal.RowBinaryHeader;

/**
 * Column metadata for a {@link ChdbResultSet}, from the types the engine declared.
 *
 * <p>Available before the first {@code next()}, because a
 * {@code RowBinaryWithNamesAndTypes} stream opens with the names and types and the cursor reads
 * them when it is constructed.
 *
 * <p>The types are the engine's own rather than Arrow's projection of them, which is the whole
 * reason for the change underneath this class: precision and scale were zero for everything
 * temporal, an array's type name was the string "Unsupported(arrow=+l)", and Nullable and
 * LowCardinality had been erased. {@link JdbcTypeMapping} holds the answers, captured from
 * clickhouse-jdbc so that a column's reported type does not change for an application moving
 * between the two drivers.
 *
 * <p>Catalog, schema and table names come back empty. The stream carries a column's name and
 * type but not its origin, and a result column often has no single origin anyway -- it may be an
 * expression, a join output or a table function's product. Returning an empty string, which
 * JDBC defines as "not applicable", is accurate; guessing a table name would not be.
 */
final class ChdbResultSetMetaData implements ResultSetMetaData {

    private final RowBinaryHeader header;

    ChdbResultSetMetaData(RowBinaryHeader header) {
        this.header = header;
    }

    private ClickHouseType type(int column) throws SQLException {
        if (column < 1 || column > header.columnCount()) {
            throw new SQLDataException(
                    "Column index "
                            + column
                            + " is out of range; this result set has "
                            + header.columnCount()
                            + " column(s), indexed from 1.",
                    "22023");
        }
        return header.types().get(column - 1);
    }

    @Override
    public int getColumnCount() {
        return header.columnCount();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        type(column);
        return header.names().get(column - 1);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        // chDB has no separate label; the SQL alias is already the Arrow field's name.
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return JdbcTypeMapping.jdbcType(type(column));
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return type(column).name();
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return JdbcTypeMapping.className(type(column));
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return JdbcTypeMapping.displaySize(type(column));
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return JdbcTypeMapping.precision(type(column));
    }

    @Override
    public int getScale(int column) throws SQLException {
        return JdbcTypeMapping.scale(type(column));
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return JdbcTypeMapping.isSigned(type(column));
    }

    @Override
    public int isNullable(int column) throws SQLException {
        // The Arrow nullable flag is authoritative: the engine sets it from the ClickHouse
        // type, so Nullable(T) and T are distinguishable rather than guessed at.
        return type(column).isNullable() ? columnNullable : columnNoNulls;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        type(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        // ClickHouse String comparison is byte-wise, so text columns are case-sensitive;
        // numbers and temporals have no case to be sensitive to.
        return JdbcTypeMapping.isCaseSensitive(type(column));
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        // Anything the engine can name can appear in a WHERE clause; whether this driver can
        // decode it is a separate question and not what searchable means.
        type(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        type(column);
        // ClickHouse has no currency type; Decimal is a plain decimal.
        return false;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        type(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        type(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        type(column);
        return false;
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        type(column);
        return "";
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        type(column);
        return "";
    }

    @Override
    public String getTableName(int column) throws SQLException {
        type(column);
        return "";
    }

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
