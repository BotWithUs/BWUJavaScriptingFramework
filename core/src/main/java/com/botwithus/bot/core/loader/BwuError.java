package com.botwithus.bot.core.loader;

/**
 * Error codes returned by bwu.dll functions.
 */
public enum BwuError {
    OK(0, "Success"),
    NOT_INITIALIZED(1, "bwu_init() has not been called"),
    ALREADY_INIT(2, "bwu_init() was already called"),
    HTTP(3, "Network request failed"),
    AUTH_TIMEOUT(4, "OAuth login timed out"),
    AUTH_FAILED(5, "Authentication was rejected or invalid"),
    NOT_LOGGED_IN(6, "Operation requires authentication"),
    INVALID_PARAM(7, "NULL pointer or out-of-range argument"),
    BUFFER_TOO_SMALL(8, "Output buffer is too small"),
    NOT_FOUND(9, "Requested resource does not exist"),
    FULL(10, "Storage at capacity"),
    PROCESS(11, "Process operation failed"),
    LOAD(12, "Module loading/injection failed"),
    DOWNLOAD(13, "Module download failed"),
    TOKEN_EXPIRED(14, "Auth token has expired"),
    SUB_EXPIRED(15, "User subscription has expired"),
    SOCKET(16, "Socket/network operation failed"),
    IO(17, "File read/write failed"),
    ALREADY(18, "Operation already in progress or completed"),
    BUSY(19, "A conflicting operation is already running"),
    MODULE_NOT_READY(20, "Module has not been downloaded yet");

    private static final BwuError[] BY_CODE;

    static {
        int max = 0;
        for (BwuError e : values()) {
            if (e.code > max) max = e.code;
        }
        BY_CODE = new BwuError[max + 1];
        for (BwuError e : values()) {
            BY_CODE[e.code] = e;
        }
    }

    private final int code;
    private final String description;

    BwuError(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static BwuError fromCode(int code) {
        if (code >= 0 && code < BY_CODE.length) {
            return BY_CODE[code];
        }
        return null;
    }
}
