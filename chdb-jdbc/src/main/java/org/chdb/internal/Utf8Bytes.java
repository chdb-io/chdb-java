package org.chdb.internal;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 bytes for the JNI boundary.
 *
 * <p>The driver's own {@code Utf8} lives in the jdbc package and is not visible here; this is
 * the same two lines for the internal side rather than a dependency in the wrong direction.
 */
final class Utf8Bytes {

    private Utf8Bytes() {
    }

    static byte[] of(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
