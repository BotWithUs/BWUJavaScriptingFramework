package com.botwithus.bot.core.shm;

/**
 * Thrown when a Win32 kernel32 downcall (via Project Panama) fails to invoke.
 *
 * <p>Wraps the {@link Throwable} from a {@code MethodHandle.invokeExact()}
 * call so callers don't have to handle the raw {@code Throwable} the FFM
 * API surfaces. The original cause is always preserved.</p>
 */
public class Kernel32Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Kernel32Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
