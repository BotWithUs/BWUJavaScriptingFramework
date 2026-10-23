package com.botwithus.bot.cli.gui.pages.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Short spoken durations for the reconnect preview: {@code 500 ms}, {@code 1.5 s}, {@code 2 min 30 s}. */
final class WaitText {

    private static final long MS_PER_SECOND = 1_000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final int SECOND_DECIMALS = 1;

    private WaitText() {
    }

    static String of(long ms) {
        if (ms < MS_PER_SECOND) {
            return ms + " ms";
        }
        long wholeSeconds = ms / MS_PER_SECOND;
        if (wholeSeconds < SECONDS_PER_MINUTE) {
            return BigDecimal.valueOf(ms).divide(BigDecimal.valueOf(MS_PER_SECOND), SECOND_DECIMALS,
                    RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " s";
        }
        long minutes = wholeSeconds / SECONDS_PER_MINUTE;
        long seconds = wholeSeconds % SECONDS_PER_MINUTE;
        return seconds == 0 ? minutes + " min" : minutes + " min " + seconds + " s";
    }
}
