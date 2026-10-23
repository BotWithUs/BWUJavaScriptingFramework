package com.botwithus.bot.core.secrets;

import java.util.Objects;

/**
 * A value that must never reach a log, a stack trace or a settings file: a
 * webhook URL, an access token.
 *
 * <p>{@link #toString()} is redacted, so a secret that ends up in a log line or
 * an exception message by accident shows as {@code Secret[…]}. Read the value
 * with {@link #reveal()}, and only where it is about to be used.</p>
 *
 * @param value the secret text; never {@code null} or blank
 */
public record Secret(String value) {

    private static final String REDACTED = "Secret[…]";

    public Secret {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a secret cannot be blank");
        }
    }

    /** The secret text. Call it where the value is used, never to log it. */
    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
