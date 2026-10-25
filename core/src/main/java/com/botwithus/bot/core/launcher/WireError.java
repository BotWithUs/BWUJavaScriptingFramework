package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.msgpack.MsgValue;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An error response's {@code error} map. {@link #code()} is the predicate;
 * {@link #message()} is for logs only.
 *
 * @param code         the stable snake_case code (ADR 2.6)
 * @param message      English text, never parsed
 * @param isRetryable  the service's {@code retryable}
 * @param retryAfterMs {@code detail.retryAfterMs}, sent with {@code rate_limited}
 * @param field        {@code detail.field}, sent with {@code bad_request}
 * @param detail       the whole {@code detail} map, for the codes that carry more in it
 */
record WireError(String code, String message, boolean isRetryable, OptionalLong retryAfterMs,
                 Optional<String> field, MsgValue detail) {

    /** The code given to an error map that names none. */
    static final String UNKNOWN_CODE = "internal";

    WireError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(retryAfterMs, "retryAfterMs");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(detail, "detail");
    }

    /** @return the error the {@code error} map describes */
    static WireError decode(MsgValue error) {
        MsgValue detail = WireValues.map(error, "detail");
        return new WireError(WireValues.string(error, "code", UNKNOWN_CODE),
                WireValues.string(error, "message", ""),
                WireValues.bool(error, "retryable", false),
                WireValues.integer(detail, "retryAfterMs"),
                WireValues.string(detail, "field"), detail);
    }

    /** @return the exception a script sees for this error */
    LauncherException toException(String method) {
        return new LauncherException(code, method + ": " + message, isRetryable, retryAfterMs, field);
    }
}
