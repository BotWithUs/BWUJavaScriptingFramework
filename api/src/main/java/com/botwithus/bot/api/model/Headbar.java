package com.botwithus.bot.api.model;

/**
 * One headbar entry on an entity, from {@link com.botwithus.bot.api.GameAPI#getHeadbars}. Entries
 * are listed as the client holds them, with no liveness filter.
 *
 * <p>There is no end value: {@link #value()} already is it. The bar interpolates from
 * {@link #valueStart()} to {@link #value()} over {@link #duration()} cycles from
 * {@link #startCycle()}. {@code duration} has read 0 in every live sample so far, so the two values
 * have never differed live.</p>
 *
 * @param headbarId  gameval HEADBAR id, such as {@link #HEALTH_DEFAULT} or
 *                   {@link #ADRENALINE_DEFAULT}; {@link #NO_TYPE} when the entry has no type
 * @param value      current value, the end of the interpolation, on a {@code 0..255} scale
 * @param valueStart where the interpolation began, on the same scale
 * @param startCycle when it began, in {@code GameSnapshot.gameCycle()} units; can lie slightly ahead
 *                   of the current cycle
 * @param duration   how long it runs, in {@code gameCycle} units
 */
public record Headbar(int headbarId, int value, int valueStart, int startCycle, int duration) {

    /** {@link #headbarId()} of an entry with no type. */
    public static final int NO_TYPE = -1;
    /** Gameval HEADBAR id of the health bar. */
    public static final int HEALTH_DEFAULT = 0;
    /** Gameval HEADBAR id of the adrenaline bar. */
    public static final int ADRENALINE_DEFAULT = 7;
    /** {@link #value()} of a full bar. */
    public static final int MAX_VALUE = 255;

    private static final double EMPTY_PERCENT = 0.0;
    private static final double FULL_PERCENT = 100.0;

    /** {@link #value()} as a percentage of a full bar, held to {@code 0.0..100.0}. */
    public double percent() {
        return Math.clamp(value * FULL_PERCENT / MAX_VALUE, EMPTY_PERCENT, FULL_PERCENT);
    }
}
