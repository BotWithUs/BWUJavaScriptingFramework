package com.botwithus.bot.cli.gui.pages.settings;

import java.math.BigDecimal;

/**
 * How a number setting is shown next to its box: the unit after it, and the
 * factor between what the box shows and what {@code config.properties} stores.
 * {@code scripts.stallAfterMs} is stored in milliseconds and shown in seconds,
 * so its unit is {@link #SECONDS_FROM_MS}: the box reads {@code 600}, the file
 * holds {@code 600000}.
 *
 * @param suffix drawn after the value, e.g. {@code "ms"}
 * @param scale  stored value = shown value × scale; {@code 1} shows the stored value as is
 */
public record DisplayUnit(String suffix, long scale) {

    private static final long MS_PER_SECOND = 1_000L;

    public static final DisplayUnit NONE = new DisplayUnit("", 1L);
    public static final DisplayUnit MILLISECONDS = new DisplayUnit("ms", 1L);
    public static final DisplayUnit SECONDS = new DisplayUnit("s", 1L);
    public static final DisplayUnit SECONDS_FROM_MS = new DisplayUnit("s", MS_PER_SECOND);
    public static final DisplayUnit TRIES = new DisplayUnit("tries", 1L);
    public static final DisplayUnit TIMES = new DisplayUnit("×", 1L);

    public DisplayUnit {
        if (scale < 1L) {
            throw new IllegalArgumentException("scale must be at least 1: " + scale);
        }
    }

    /** {@code true} when the box shows something other than the stored number. */
    public boolean isScaled() {
        return scale != 1L;
    }

    /** The stored text as the box shows it: {@code "600000"} → {@code "600"}. Non-numbers pass through. */
    public String shown(String stored) {
        try {
            return new BigDecimal(stored.strip()).divide(BigDecimal.valueOf(scale))
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException e) {
            return stored;
        }
    }

    /** One stored bound as the box shows it. */
    public String shown(long stored) {
        return shown(Long.toString(stored));
    }

    /**
     * What the user typed, as the text to store: {@code "2.5"} seconds →
     * {@code "2500"}. Unscaled units pass the text through for the setting to check.
     *
     * @throws IllegalArgumentException if a scaled value is not a number, or not a whole number once scaled
     */
    public String stored(String shown) {
        if (!isScaled()) {
            return shown.strip();
        }
        try {
            BigDecimal value = new BigDecimal(shown.strip()).multiply(BigDecimal.valueOf(scale));
            return value.toBigIntegerExact().toString();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + shown.strip() + "' is not a number", e);
        }
    }
}
