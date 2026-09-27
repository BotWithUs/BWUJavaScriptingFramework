package com.botwithus.bot.core.alerts;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A daily window in which alerts are held back, except the kinds that
 * {@link AlertKind#isPassingQuietHours() pass quiet hours} (crashes). Whether an
 * alert held back is sent when the window {@link #endAfter ends} or dropped is the
 * caller's choice; this type only knows the window.
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

    /**
     * When the window that holds {@code now} ends: the first {@code to} after
     * {@code now}, in {@code now}'s zone. Meaningful only while {@link #contains}
     * says {@code now} is inside the window.
     */
    public ZonedDateTime endAfter(ZonedDateTime now) {
        LocalDate day = now.toLocalDate();
        ZonedDateTime end = ZonedDateTime.of(day, to, now.getZone());
        return end.isAfter(now) ? end : ZonedDateTime.of(day.plusDays(1), to, now.getZone());
    }

    /** Whether an alert of {@code kind} may be sent at {@code time}. */
    public boolean lets(AlertKind kind, LocalTime time) {
        return kind.isPassingQuietHours() || !contains(time);
    }
}
