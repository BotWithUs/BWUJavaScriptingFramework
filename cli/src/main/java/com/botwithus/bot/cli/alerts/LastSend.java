package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.SendResult;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * The most recent send to a service, test or alert.
 *
 * @param isTest  whether it was a Send test
 * @param result  how it went
 * @param subject for an alert, what it was about (e.g. {@code Hollowmere stopped responding}); empty for a test
 */
public record LastSend(boolean isTest, SendResult result, String subject) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public LastSend {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(subject, "subject");
    }

    /** A Send test. */
    public static LastSend test(SendResult result) {
        return new LastSend(true, result, "");
    }

    /** An alert about {@code subject}. */
    public static LastSend alert(SendResult result, String subject) {
        return new LastSend(false, result, subject);
    }

    /**
     * The line an Integrations card shows under Send test:
     * {@code Test delivered 14:05 · 180 ms}, {@code Last sent 14:05 · Hollowmere stopped responding},
     * or on failure {@code 401 Unauthorized · Unknown Webhook (14:02)}.
     *
     * @param zone the zone the time is shown in
     */
    public String line(ZoneId zone) {
        String time = TIME.format(result.at().atZone(zone));
        return switch (result) {
            case SendResult.Delivered delivered -> isTest
                    ? "Test delivered " + time + SendResult.SEPARATOR + delivered.elapsed().toMillis() + " ms"
                    : "Last sent " + time + SendResult.SEPARATOR + subject;
            case SendResult.Failed failed -> failed.summary() + " (" + time + ")";
        };
    }
}
