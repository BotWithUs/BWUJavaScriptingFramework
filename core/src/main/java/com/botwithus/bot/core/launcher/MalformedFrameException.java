package com.botwithus.bot.core.launcher;

/** A frame from the launcher service that is not a valid envelope. The connection cannot be trusted after it. */
final class MalformedFrameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MalformedFrameException(String message) {
        super(message);
    }

    MalformedFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
