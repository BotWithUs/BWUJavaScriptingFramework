package com.botwithus.bot.api.util;

import java.time.Duration;

/** Formatting and rate helpers for script statistics ("xp/hr", "runtime 01:23:45"). */
public final class Durations {

    private static final long SECONDS_PER_MINUTE = 60;
    private static final long MINUTES_PER_HOUR = 60;
    private static final long HOURS_PER_DAY = 24;
    private static final double MS_PER_HOUR = Duration.ofHours(1).toMillis();

    private Durations() {
    }

    /**
     * {@code HH:MM:SS}, or {@code Nd HH:MM:SS} from a day up. Negative durations format as
     * zero; sub-second parts are dropped.
     */
    public static String format(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        long totalMinutes = totalSeconds / SECONDS_PER_MINUTE;
        long minutes = totalMinutes % MINUTES_PER_HOUR;
        long totalHours = totalMinutes / MINUTES_PER_HOUR;
        long hours = totalHours % HOURS_PER_DAY;
        long days = totalHours / HOURS_PER_DAY;
        String clock = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return days == 0 ? clock : days + "d " + clock;
    }

    /** {@link #format(Duration)} for a millisecond count. */
    public static String formatMs(long ms) {
        return format(Duration.ofMillis(ms));
    }

    /**
     * {@code count} scaled to an hourly rate over {@code elapsed}, rounded down; {@code 0}
     * when no time has elapsed, so a stat panel never divides by zero on its first frame.
     */
    public static long perHour(long count, Duration elapsed) {
        long ms = elapsed.toMillis();
        if (ms <= 0) {
            return 0;
        }
        return (long) (count * MS_PER_HOUR / ms);
    }
}
