package com.botwithus.bot.skilling.atlas;

/** Unchecked failure reading the Atlas ({@code resolved.sqlite}). */
public final class AtlasException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AtlasException(String message, Throwable cause) {
        super(message, cause);
    }

    public AtlasException(String message) {
        super(message);
    }
}
