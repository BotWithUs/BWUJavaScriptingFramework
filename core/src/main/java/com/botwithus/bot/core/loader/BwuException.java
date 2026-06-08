package com.botwithus.bot.core.loader;

/**
 * Thrown when a bwu.dll function returns a non-OK error code.
 */
public class BwuException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final BwuError error;

    public BwuException(BwuError error) {
        super(error.description());
        this.error = error;
    }

    public BwuException(BwuError error, String nativeMessage) {
        super(nativeMessage != null && !nativeMessage.isEmpty() ? nativeMessage : error.description());
        this.error = error;
    }

    public BwuException(String message, Throwable cause) {
        super(message, cause);
        this.error = null;
    }

    public BwuError error() {
        return error;
    }
}
