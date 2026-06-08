package com.botwithus.bot.core.cache;

/**
 * Thrown when a {@link NXTCache} downcall returns a non-OK error code or
 * the underlying FFM invocation fails.
 *
 * <p>Wraps the producer-side error string from {@code nxt_last_error()}
 * (or the originating {@link Throwable} when an FFM call surfaces one) so
 * callers see a typed exception instead of a bare {@link RuntimeException}.</p>
 */
public class NXTCacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NXTCacheException(String message) {
        super(message);
    }

    public NXTCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
