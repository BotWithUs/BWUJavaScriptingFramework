package com.botwithus.bot.cli.gui.pages.installed;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The page's short times: a clock time, when a file changed, and how long a script has run. */
final class WhenText {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("'today' HH:mm", Locale.ROOT);
    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter OLDER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;

    private WhenText() {}

    /** {@code 14:03:52} in {@code zone}. */
    static String clock(Instant at, ZoneId zone) {
        return CLOCK.format(at.atZone(zone));
    }

    /** {@code today 13:58}, {@code Sep 12}, or {@code Sep 12, 2025}, depending on how long ago {@code at} was. */
    static String changed(Instant at, Instant now, ZoneId zone) {
        ZonedDateTime when = at.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        if (when.toLocalDate().equals(today)) {
            return TODAY.format(when);
        }
        return when.getYear() == today.getYear() ? THIS_YEAR.format(when) : OLDER.format(when);
    }

    /** {@code 0:48}, {@code 41:07} or {@code 2:02:44}. Negative spans read as zero. */
    static String uptime(Duration span) {
        long total = Math.max(0L, span.getSeconds());
        long hours = total / SECONDS_PER_HOUR;
        long minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
        long seconds = total % SECONDS_PER_MINUTE;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
