package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Human-like delay utilities to make bot behavior less detectable.
 */
public final class Humanize {

    /** Probability per loop tick that a "micro-break" pause is injected. */
    private static final double MICRO_BREAK_PROBABILITY = 0.05;
    /** Lower bound of the micro-break pause, inclusive (ms). */
    private static final long MICRO_BREAK_MIN_MS = 2000L;
    /** Upper bound of the micro-break pause, exclusive (ms). */
    private static final long MICRO_BREAK_MAX_EXCLUSIVE_MS = 5001L;

    private Humanize() {}

    /**
     * Returns a gaussian-bounded delay in milliseconds, clamped to [{@code min}, {@code max}].
     *
     * @param mean   the mean delay
     * @param stdDev the standard deviation
     * @param min    minimum allowed delay
     * @param max    maximum allowed delay
     * @return a randomized delay in milliseconds
     */
    public static long delay(long mean, long stdDev, long min, long max) {
        long value = Timing.gaussianRandom(mean, stdDev);
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Sleeps for a gaussian-distributed duration.
     *
     * @param mean   the mean sleep time in milliseconds
     * @param stdDev the standard deviation in milliseconds
     */
    public static void sleep(long mean, long stdDev) {
        Timing.sleep(delay(mean, stdDev, 0, mean * 3));
    }

    /**
     * Returns a loop delay with variance and a 5% chance of a micro-break (2-5s).
     *
     * @param baseMs         the base delay in milliseconds
     * @param varianceFactor multiplier for the variance (e.g., 0.3 = 30% of base)
     * @return the computed delay in milliseconds
     */
    public static int loopDelay(int baseMs, double varianceFactor) {
        long variance = (long) (baseMs * varianceFactor);
        long delay = Math.max(baseMs / 2, Timing.gaussianRandom(baseMs, variance));

        if (ThreadLocalRandom.current().nextDouble() < MICRO_BREAK_PROBABILITY) {
            delay += ThreadLocalRandom.current().nextLong(MICRO_BREAK_MIN_MS, MICRO_BREAK_MAX_EXCLUSIVE_MS);
        }

        return (int) delay;
    }

    /**
     * Returns {@code true} with the given probability.
     *
     * @param probability a value between 0.0 (never) and 1.0 (always)
     * @return {@code true} if the random check passed
     */
    public static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
