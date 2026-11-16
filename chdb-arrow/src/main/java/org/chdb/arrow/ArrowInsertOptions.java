package org.chdb.arrow;

/** Per-insert choices for Arrow input. The default follows the engine settings. */
public final class ArrowInsertOptions {
    public static final ArrowInsertOptions DEFAULT = new ArrowInsertOptions(0);

    /** Reject a source NULL when the destination column is non-nullable. */
    public static final ArrowInsertOptions REJECT_NULL_AS_DEFAULT = new ArrowInsertOptions(1);

    private final int nativeFlags;

    private ArrowInsertOptions(int nativeFlags) {
        this.nativeFlags = nativeFlags;
    }

    int nativeFlags() {
        return nativeFlags;
    }
}
