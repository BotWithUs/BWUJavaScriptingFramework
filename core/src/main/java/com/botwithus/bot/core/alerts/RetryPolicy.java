package com.botwithus.bot.core.alerts;

import java.time.Duration;
import java.util.Objects;

/**
 * How hard {@link HttpDelivery} tries.
 *
 * @param maxAttempts        attempts in all, the first included; at least 1
 * @param firstBackoff       wait before the second attempt
 * @param multiplier         each later wait is the one before times this; at least 1
 * @param longestRetryAfter  the longest {@code retry_after} a rate-limited reply may ask
 *                           for; a longer one fails the send rather than holding it
 */
public record RetryPolicy(int maxAttempts, Duration firstBackoff, double multiplier,
                          Duration longestRetryAfter) {

    /** Three attempts, 1 s then 2 s apart; honour a rate limit of up to a minute. */
    public static final RetryPolicy DEFAULT =
            new RetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1));

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts " + maxAttempts);
        }
        Objects.requireNonNull(firstBackoff, "firstBackoff");
        Objects.requireNonNull(longestRetryAfter, "longestRetryAfter");
        if (!(multiplier >= 1.0)) {
            throw new IllegalArgumentException("multiplier " + multiplier);
        }
    }

    /** The wait after failed attempt {@code attempt} (1-based) before the next one. */
    public Duration backoffAfter(int attempt) {
        double factor = Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis(Math.round(firstBackoff.toMillis() * factor));
    }
}
