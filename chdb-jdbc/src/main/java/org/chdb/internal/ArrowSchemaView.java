package org.chdb.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A streaming result's column list, read once from the Arrow schema.
 *
 * <p>The schema is stable for the whole lifetime of a chDB Arrow stream, so it is fetched at
 * open and never again -- which is also what lets {@code getMetaData()} answer before the
 * first {@code next()}.
 */
public final class ArrowSchemaView {

    private final String[] names;
    private final ArrowFieldType[] types;

    /**
     * @param names column names from the Arrow schema, in ordinal order
     * @param formats Arrow C Data Interface format strings, parallel to {@code names}
     * @param nullable per-column {@code ARROW_FLAG_NULLABLE}, parallel to {@code names}
     */
    public ArrowSchemaView(String[] names, String[] formats, boolean[] nullable) {
        if (names.length != formats.length || names.length != nullable.length) {
            throw new IllegalStateException(
                    "the shim returned mismatched schema arrays: "
                            + names.length
                            + " names, "
                            + formats.length
                            + " formats, "
                            + nullable.length
                            + " nullability flags");
        }
        this.names = new String[names.length];
        this.types = new ArrowFieldType[names.length];
        for (int i = 0; i < names.length; i++) {
            // An expression without an alias can arrive unnamed. JDBC callers index by label,
            // so give it the one label that is always available and never collides oddly.
            this.names[i] = names[i] == null || names[i].isEmpty() ? "column_" + (i + 1) : names[i];
            // Dictionary encoding is detected in the shim, which reports such a column with an
            // unparseable layout; there is no separate flag to pass through here.
            this.types[i] = ArrowFieldType.parse(formats[i], nullable[i], false);
        }
    }

    public int columnCount() {
        return names.length;
    }

    /** Column name by 0-based index. */
    public String columnName(int index) {
        return names[index];
    }

    public List<String> columnNames() {
        return Collections.unmodifiableList(Arrays.asList(names));
    }

    /** Column type by 0-based index. */
    public ArrowFieldType typeRef(int index) {
        return types[index];
    }

    /**
     * The type array, shared with {@link ArrowBatch} rather than copied.
     *
     * <p>Shared on purpose: a batch is constructed per fetch and copying the array each time
     * would allocate per batch for data that never changes. {@link ArrowFieldType} is
     * immutable, so sharing it is safe.
     */
    public ArrowFieldType[] types() {
        return types;
    }
}
