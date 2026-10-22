package com.botwithus.bot.core.voice;

import java.util.HexFormat;

/** A SAPI or COM call failed, either with an {@code HRESULT} or inside the binding. */
public final class SapiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SapiException(String what, int hresult) {
        super(what + " failed: HRESULT 0x" + HexFormat.of().toHexDigits(hresult));
    }

    SapiException(String what, Throwable cause) {
        super(what + " failed: " + cause, cause);
    }

    SapiException(String message) {
        super(message);
    }
}
