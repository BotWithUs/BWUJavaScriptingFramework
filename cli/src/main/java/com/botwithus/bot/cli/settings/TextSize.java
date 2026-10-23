package com.botwithus.bot.cli.settings;

/**
 * UI text scale ({@code ui.textSize}). {@link #MATCH_WINDOWS} follows the monitor's
 * display scaling; the others pin a percentage.
 */
public enum TextSize {
    MATCH_WINDOWS(0),
    PERCENT_100(100),
    PERCENT_125(125),
    PERCENT_150(150),
    PERCENT_175(175);

    private final int percent;

    TextSize(int percent) {
        this.percent = percent;
    }

    /** The pinned scale in percent, or {@code 0} for {@link #MATCH_WINDOWS}. */
    public int percent() {
        return percent;
    }
}
