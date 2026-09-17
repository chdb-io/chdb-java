package org.chdb.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The names and types a {@code RowBinaryWithNamesAndTypes} stream begins with.
 *
 * <p>This is the whole reason for reading this format rather than Arrow: the types here are the
 * ones the engine declared, so {@code Enum8('a' = 6, 'b' = 7)} arrives with its labels and
 * {@code IPv6} does not arrive indistinguishable from a {@code UUID}.
 */
public final class RowBinaryHeader {

    private final List<String> names;
    private final List<ClickHouseType> types;

    private RowBinaryHeader(List<String> names, List<ClickHouseType> types) {
        this.names = Collections.unmodifiableList(names);
        this.types = Collections.unmodifiableList(types);
    }

    public int columnCount() { return names.size(); }

    public List<String> names() { return names; }

    public List<ClickHouseType> types() { return types; }

    /**
     * Reads the header, leaving the cursor on the first row.
     *
     * <p>Every column's name and then every column's type, each a length-prefixed string, after
     * a count. A type that does not parse is kept as {@link ClickHouseType.Kind#UNKNOWN} rather
     * than failing the statement: the caller can still be told what the column is called and
     * what the engine called its type.
     */
    public static RowBinaryHeader read(RowBinaryInput in) {
        long count = in.readVarUInt();
        if (count < 0 || count > 1_000_000) {
            throw new IllegalStateException("implausible column count " + count + " in RowBinary header");
        }
        int n = (int) count;
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(in.readString());
        }
        List<ClickHouseType> types = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String raw = in.readString();
            try {
                types.add(ClickHouseType.parse(raw));
            } catch (RuntimeException e) {
                types.add(ClickHouseType.unknown(raw));
            }
        }
        return new RowBinaryHeader(names, types);
    }
}
