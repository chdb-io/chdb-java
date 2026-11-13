package org.chdb.arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArrowOptionsTest {
    @Test
    void namedChoicesComposeWithoutMutatingDefault() {
        ArrowOptions options = ArrowOptions.DEFAULT.withLowCardinalityAsDictionary()
                .withStringAsBinary().withUnsupportedAsBinary();

        assertEquals(4, ArrowOptions.DEFAULT.nativeFlags());
        assertEquals(6, ArrowOptions.DEFAULT.withLowCardinalityAsDictionary().nativeFlags());
        assertEquals(0, ArrowOptions.DEFAULT.withStringAsBinary().nativeFlags());
        assertEquals(5, ArrowOptions.DEFAULT.withUnsupportedAsBinary().nativeFlags());
        assertEquals(3, options.nativeFlags());
    }
}
