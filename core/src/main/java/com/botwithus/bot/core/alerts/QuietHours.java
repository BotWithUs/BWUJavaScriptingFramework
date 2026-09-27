package com.botwithus.bot.core.alerts;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A daily window in which alerts are held back, except the kinds that
 * {@link AlertKind#isPassingQuietHours() pass quiet hours} (crashes). An alert
 * held back is dropped, not sent later: by morning it is stale, and the daily
 * summary covers the night.
 *
 * <p>The window starts at {@code from} (inclusive) and ends at {@code to}
 * (exclusive), in local time. When {@code from} is after {@code to} the window
 * wraps past midnight: 22:00–07:00 is quiet from 22:00 to 06:59. When they are
 * equal the window is empty.</p>
 *
 * @param from start of the window, inclusive
 * @param to   end of the window, exclusive
 */
public record QuietHours(LocalTime from, LocalTime to) {

    public QuietHours {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** Whether {@code time} is inside the window. */
    public boolean contains(LocalTime time) {
        if (from.isBefore(to)) {
            return !time.isBefore(from) && time.isBefore(to);
        }
        if (from.isAfter(to)) {
            return !time.isBefore(from) || time.isBefore(to);
        }
        return false;
    }

    /** Whether an alert of {@code kind} may be sent at {@code time}. */
    public boolean lets(AlertKind kind, LocalTime time) {
        return kind.isPassingQuietHours() || !contains(time);
    }
}
