package com.botwithus.bot.core.alerts;

import java.time.Duration;
import java.util.Optional;

/**
 * Reads how long a rate-limited (429) reply asks the sender to wait. A service
 * whose limit is worth waiting out supplies one; for the rest a 429 is final,
 * like any other 4xx.
 */
@FunctionalInterface
public interface RateLimitReader {

    /** A 429 is never retried. */
    RateLimitReader NONE = reply -> Optional.empty();

    /** How long {@code reply} asks to wait before trying again, if it says. */
    Optional<Duration> retryAfter(HttpReply reply);
}
