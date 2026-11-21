package com.botwithus.bot.core.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How one send the launcher made ended: its {@code alerts.result} event. Every
 * field is safe to show; the launcher never puts a webhook URL or a token in one.
 *
 * @param deliveryId the id the delivery or test was given when it was queued
 * @param service    where it went
 * @param isTest     whether it was a Send test
 * @param kind       the kind the host gave it
 * @param outcome    the predicate
 * @param status     what went wrong in a few words, e.g. {@code 429}; empty when delivered
 * @param reason     the detail, e.g. the service's own error text; possibly empty
 * @param attempts   attempts made, the first included
 * @param elapsed    from the first attempt to the last
 * @param at         when it finished
 */
public record AlertResult(long deliveryId, AlertService service, boolean isTest, String kind, Outcome outcome,
                          String status, String reason, int attempts, Duration elapsed, Instant at) {

    /** What {@link #status()} says when a message waited too long and was not sent. */
    public static final String STALE_STATUS = "Not sent";
    /** What the reason says, when the launcher gave none, for a message that waited too long. */
    public static final String STALE_REASON = "it waited too long to go out";
    /** What the status says when a send failed and the launcher gave no status. */
    public static final String FAILED_STATUS = "Failed";

    /** How a send ended. */
    public enum Outcome {
        /** The service accepted the message. */
        DELIVERED,
        /** The service refused it, or could not be reached, after every attempt. */
        FAILED,
        /** It waited in the launcher's queue too long and was dropped unsent. */
        STALE
    }

    public AlertResult {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(at, "at");
    }

    /** This result, worded as the Integrations card shows a send. */
    public SendResult toSendResult() {
        return switch (outcome) {
            case DELIVERED -> new SendResult.Delivered(at, elapsed, attempts);
            case FAILED -> new SendResult.Failed(at, status.isEmpty() ? FAILED_STATUS : status, reason, attempts);
            case STALE -> new SendResult.Failed(at, STALE_STATUS, reason.isEmpty() ? STALE_REASON : reason,
                    attempts);
        };
    }
}
