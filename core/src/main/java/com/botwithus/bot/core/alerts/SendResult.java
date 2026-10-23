package com.botwithus.bot.core.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How one send went, worded for the user.
 *
 * <p>{@link #summary()} is the {@code status · reason} line the Integrations page
 * shows next to Send test, e.g. {@code 401 Unauthorized · Unknown Webhook}. It never
 * contains a secret: URLs in it are {@link Redaction redacted} to their host.</p>
 */
public sealed interface SendResult {

    /** Separates the status from the reason in {@link #summary()}. */
    String SEPARATOR = " · ";

    /** When the send finished. */
    Instant at();

    /** Attempts made, the first included. */
    int attempts();

    /** Whether the service accepted the message. */
    boolean isDelivered();

    /** {@code status · reason}, or just the status when there is no reason. */
    String summary();

    /**
     * The service accepted the message.
     *
     * @param elapsed from the first attempt to the accepted one
     */
    record Delivered(Instant at, Duration elapsed, int attempts) implements SendResult {

        public Delivered {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(elapsed, "elapsed");
        }

        @Override
        public boolean isDelivered() {
            return true;
        }

        @Override
        public String summary() {
            return "Delivered" + SEPARATOR + elapsed.toMillis() + " ms";
        }
    }

    /**
     * The message was not delivered.
     *
     * @param status what went wrong in a few words, e.g. {@code 401 Unauthorized} or {@code Timed out}
     * @param reason the detail, e.g. the service's own error text; possibly empty
     */
    record Failed(Instant at, String status, String reason, int attempts) implements SendResult {

        public Failed {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean isDelivered() {
            return false;
        }

        @Override
        public String summary() {
            return reason.isEmpty() ? status : status + SEPARATOR + reason;
        }
    }
}
