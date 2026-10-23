package com.botwithus.bot.core.secrets;

import java.util.OptionalInt;

/**
 * The backing credential store refused or failed a call. The message names the
 * operation and the target, never the secret.
 */
public final class CredentialStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final int NO_ERROR_CODE = -1;

    private final int errorCode;

    /** A failure the operating system reported with {@code errorCode} (a Win32 error). */
    public CredentialStoreException(String message, int errorCode) {
        super(message + " (error " + errorCode + ")");
        this.errorCode = errorCode;
    }

    /** A failure with no operating-system error code, such as a missing library. */
    public CredentialStoreException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = NO_ERROR_CODE;
    }

    /** The operating system's error code, when it reported one. */
    public OptionalInt errorCode() {
        return errorCode == NO_ERROR_CODE ? OptionalInt.empty() : OptionalInt.of(errorCode);
    }
}
