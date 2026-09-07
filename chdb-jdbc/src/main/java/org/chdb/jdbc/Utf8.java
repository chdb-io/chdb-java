package org.chdb.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Encodes text for the JNI boundary.
 *
 * <p>Standard UTF-8 byte arrays, never {@code String}. JNI's {@code GetStringUTFChars} hands
 * out modified UTF-8, which encodes a supplementary character as a surrogate pair of
 * three-byte sequences and cannot represent U+0000 at all -- so a query holding an emoji or a
 * parameter holding a NUL would arrive at the engine corrupted. Encoding here and calling the
 * length-carrying {@code _n} entry points avoids both (work plan section 5.5).
 */
final class Utf8 {

    /** The empty two-dimensional array the shim reads as "no parameters". */
    static final byte[][] NONE = new byte[0][];

    private Utf8() {
    }

    static byte[] encode(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[][] encodeAll(Collection<String> values) {
        byte[][] out = new byte[values.size()][];
        int i = 0;
        for (String value : values) {
            out[i++] = encode(value);
        }
        return out;
    }
}
