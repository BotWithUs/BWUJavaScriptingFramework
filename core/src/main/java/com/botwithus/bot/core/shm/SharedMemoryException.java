package com.botwithus.bot.core.shm;

/**
 * Raised when binding to or reading from the cross-process shared region
 * fails — bad magic, version mismatch, kernel call failure, or torn read
 * (writer wrapped a slot before we finished consuming it). Unchecked so it
 * doesn't pollute the API surface; callers that care about specific failure
 * modes should inspect the message.
 */
public class SharedMemoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SharedMemoryException(String message) {
        super(message);
    }

    public SharedMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
