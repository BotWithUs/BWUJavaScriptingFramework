package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Human-like delay utilities to make bot behavior less detectable.
 */
public final class Humanize {

    private Humanize() {}

    /**
     * Returns a gaussian-bounded delay in milliseconds.
     */
    public static long delay(long mean, long stdDev, long min, long max) {
        long value = (long) (mean + ThreadLocalRandom.current().nextGaussian() * stdDev);
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Sleeps for a gaussian-distributed duration.
     */
    public static void sleep(long mean, long stdDev) {
        Timing.sleep(delay(mean, stdDev, 0, mean * 3));
    }

    /**
     * Returns a loop delay with variance and a 5% chance of a micro-break.
     */
    public static int loopDelay(int baseMs, double varianceFactor) {
        double variance = baseMs * varianceFactor;
        long delay = (long) (baseMs + ThreadLocalRandom.current().nextGaussian() * variance);
        delay = Math.max(baseMs / 2, delay);

        // 5% chance of micro-break (2-5 seconds)
        if (ThreadLocalRandom.current().nextDouble() < 0.05) {
            delay += ThreadLocalRandom.current().nextLong(2000, 5001);
        }

        return (int) delay;
    }

    /**
     * Returns true with the given probability (0.0 to 1.0).
     */
    public static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
