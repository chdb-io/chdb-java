package org.chdb.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.chdb.internal.ArrowFieldType;
import org.chdb.internal.ArrowSchemaView;

/**
 * Column metadata for a {@link ChdbResultSet}, derived from the Arrow schema.
 *
 * <p>Available before the first {@code next()}, because the shim fetches the first batch when
 * it opens the stream and that is what produces the schema.
 *
 * <p>Catalog, schema and table names come back empty. An Arrow schema carries a column's name
 * and type but not its origin, and a result column often has no single origin anyway -- it may
 * be an expression, a join output or a table function's product. Returning an empty string,
 * which JDBC defines as "not applicable", is accurate; guessing a table name would not be.
 */
final class ChdbResultSetMetaData implements ResultSetMetaData {

    private final ArrowSchemaView schema;

    ChdbResultSetMetaData(ArrowSchemaView schema) {
        this.schema = schema;
    }

    private ArrowFieldType type(int column) throws SQLException {
        if (column < 1 || column > schema.columnCount()) {
            throw new SQLDataException(
                    "Column index "
                            + column
                            + " is out of range; this result set has "
                            + schema.columnCount()
                            + " column(s), indexed from 1.",
                    "22023");
        }
        return schema.typeRef(column - 1);
    }

    @Override
    public int getColumnCount() {
        return schema.columnCount();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        type(column);
        return schema.columnName(column - 1);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        // chDB has no separate label; the SQL alias is already the Arrow field's name.
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return type(column).jdbcType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return type(column).typeName();
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return type(column).javaClass().getName();
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return type(column).displaySize();
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return type(column).jdbcPrecision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        return type(column).scale();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return type(column).signed();
    }

    @Override
    public int isNullable(int column) throws SQLException {
        // The Arrow nullable flag is authoritative: the engine sets it from the ClickHouse
        // type, so Nullable(T) and T are distinguishable rather than guessed at.
        return type(column).nullable() ? columnNullable : columnNoNulls;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        type(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        ArrowFieldType columnType = type(column);
        // ClickHouse String comparison is byte-wise, so text columns are case-sensitive;
        // numbers and temporals have no case to be sensitive to.
        switch (columnType.kind()) {
            case UTF8:
            case LARGE_UTF8:
            case FIXED_SIZE_BINARY:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        // Every supported scalar type can appear in a WHERE clause.
        return type(column).supported();
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
