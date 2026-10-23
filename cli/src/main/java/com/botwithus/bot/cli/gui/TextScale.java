package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.TextSize;

/**
 * The scale every font and spacing is drawn at, from the {@code ui.textSize}
 * setting: the monitor's own scaling for {@link TextSize#MATCH_WINDOWS}, or the
 * pinned percentage.
 */
public final class TextScale {

    private static final float PERCENT = 100f;

    private TextScale() {
    }

    /**
     * @param monitorScale the monitor's content scale, e.g. {@code 1.5f} at 150% display scaling
     */
    public static float of(TextSize size, float monitorScale) {
        return switch (size) {
            case MATCH_WINDOWS -> monitorScale;
            case PERCENT_100, PERCENT_125, PERCENT_150, PERCENT_175 -> size.percent() / PERCENT;
        };
    }

    /** {@code scale} as a whole percentage, for "Match Windows (150%)". */
    public static int percent(float scale) {
        return Math.round(scale * PERCENT);
    }
}
