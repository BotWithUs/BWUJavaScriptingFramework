package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.pages.groups.GroupText;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalDouble;

/** The page's small pieces of text: clocks, loop times and counts. */
final class ManagementText {

    /** Shown for a value there is none of. */
    static final String NONE = "—";

    private ManagementText() {}

    /** {@code 0:48}, {@code 42:10} or {@code 2:14:03}, as the Groups page's manager slot writes it. */
    static String uptime(Duration span) {
        return GroupText.clock(span);
    }

    /** A loop time in whole milliseconds, without the unit; a dash when there is none. */
    static String loopMs(OptionalDouble ms) {
        return ms.isPresent() ? Long.toString(Math.round(ms.getAsDouble())) : NONE;
    }

    /** "1 client" / "3 clients". */
    static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** "2,531": a count with thousands separators. */
    static String thousands(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
