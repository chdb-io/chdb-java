package org.chdb.internal;

/**
 * This OS, CPU or libc has no chDB V1 native package.
 *
 * <p>Raised during platform detection, before any shared library is opened, so that the
 * failure names the detected triple and the supported set instead of surfacing as an
 * {@link UnsatisfiedLinkError} with no context (work plan section 5.4).
 */
public class UnsupportedPlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedPlatformException(String message) {
        super(message);
    }
}
