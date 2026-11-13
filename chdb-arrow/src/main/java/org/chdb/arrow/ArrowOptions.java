package org.chdb.arrow;

/**
 * Explicit core Arrow output type choices. Defaults reject unsupported types, materialize
 * LowCardinality, and expose String as UTF-8. Alternative mappings can lose type fidelity;
 * callers opt into them rather than silently changing JDBC or Arrow defaults.
 */
public final class ArrowOptions {
    public static final ArrowOptions DEFAULT = new ArrowOptions(false, false, true);

    private final boolean unsupportedAsBinary;
    private final boolean lowCardinalityAsDictionary;
    private final boolean stringAsString;

    public ArrowOptions(boolean unsupportedAsBinary, boolean lowCardinalityAsDictionary,
            boolean stringAsString) {
        this.unsupportedAsBinary = unsupportedAsBinary;
        this.lowCardinalityAsDictionary = lowCardinalityAsDictionary;
        this.stringAsString = stringAsString;
    }

    /** Opt into lossy binary output for types without a faithful Arrow mapping. */
    public ArrowOptions withUnsupportedAsBinary() {
        return new ArrowOptions(true, lowCardinalityAsDictionary, stringAsString);
    }

    /** Export LowCardinality values as Arrow dictionaries instead of materializing them. */
    public ArrowOptions withLowCardinalityAsDictionary() {
        return new ArrowOptions(unsupportedAsBinary, true, stringAsString);
    }

    /** Export ClickHouse String as Arrow binary instead of the default UTF-8. */
    public ArrowOptions withStringAsBinary() {
        return new ArrowOptions(unsupportedAsBinary, lowCardinalityAsDictionary, false);
    }

    int nativeFlags() {
        return (unsupportedAsBinary ? 1 : 0)
                | (lowCardinalityAsDictionary ? 2 : 0)
                | (stringAsString ? 4 : 0);
    }
}
