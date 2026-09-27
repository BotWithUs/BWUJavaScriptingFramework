package com.botwithus.bot.cli.gui.pages.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How the Dashboard writes numbers, times and durations. One place, so every panel agrees. */
final class DashFormat {

    /** Shown where there is no value, rather than a misleading zero. */
    static final String NONE = "—";

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK_MS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3_600L;
    private static final double MS_PER_SECOND = 1_000.0;

    private DashFormat() {}

    /** "14:03:52". */
    static String clock(Instant at) {
        return CLOCK.format(at);
    }

    /** "14:03:52.009", for log lines. */
    static String clockMillis(Instant at) {
        return CLOCK_MS.format(at);
    }

    /** "06:12" under an hour, "1:02:03" from one. Never negative. */
    static String elapsed(Duration d) {
        long s = Math.max(0L, d.getSeconds());
        long h = s / SECONDS_PER_HOUR;
        long m = (s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long sec = s % SECONDS_PER_MINUTE;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    /** "38 s", "4 m 5 s", "2 h 10 m": a short span a person reads at a glance. */
    static String span(Duration d) {
        long s = Math.max(0L, d.getSeconds());
        if (s < SECONDS_PER_MINUTE) {
            return s + " s";
        }
        if (s < SECONDS_PER_HOUR) {
            return (s / SECONDS_PER_MINUTE) + " m " + (s % SECONDS_PER_MINUTE) + " s";
        }
        return (s / SECONDS_PER_HOUR) + " h " + ((s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE) + " m";
    }

    /** A delay in milliseconds as seconds: "8 s", "0.5 s". */
    static String delay(long ms) {
        double seconds = ms / MS_PER_SECOND;
        return seconds == Math.rint(seconds)
                ? String.format(Locale.ROOT, "%d s", (long) seconds)
                : String.format(Locale.ROOT, "%.1f s", seconds);
    }

    /** A whole count with thousands separators: "18,420". */
    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A loop time in whole milliseconds with separators, or {@link #NONE} for none. */
    static String loopMs(double ms) {
        return ms > 0 ? count(Math.round(ms)) : NONE;
    }

    /** An RPC latency with one decimal: "3.1". */
    static String rpcMs(double ms) {
        return String.format(Locale.ROOT, "%.1f", ms);
    }
}
