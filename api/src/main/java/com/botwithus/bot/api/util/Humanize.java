package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

import com.botwithus.bot.api.runtime.SessionPacing;

/**
 * Human-like delay utilities to make bot behavior less detectable.
 *
 * <h2>When the agent owns timing</h2>
 * An agent whose session layer is active decides when each queued action is pressed, and
 * plans its own idle runs and breaks (see {@link SessionPacing}). A humanised delay on top
 * of that plan compounds with it, and the sum looks less human than either part. So while
 * {@link SessionPacing#current()} is active, {@link #loopDelay} and {@link #sleep} step
 * back: they wait only for the agent's next planned press, and add no jitter or micro-break
 * of their own. With an older agent, or off a script thread, they behave exactly as before.
 * {@link #delay} and {@link #chance} only compute values and are unchanged.
 */
public final class Humanize {

    /** Probability per loop tick that a "micro-break" pause is injected. */
    private static final double MICRO_BREAK_PROBABILITY = 0.05;
    /** Lower bound of the micro-break pause, inclusive (ms). */
    private static final long MICRO_BREAK_MIN_MS = 2000L;
    /** Upper bound of the micro-break pause, exclusive (ms). */
    private static final long MICRO_BREAK_MAX_EXCLUSIVE_MS = 5001L;
    /**
     * Shortest loop delay while the agent owns timing and no press is pending: long enough
     * that a script does not spin on its loop, short enough to add nothing a person would
     * see next to the agent's planned gap.
     */
    static final int MIN_DEFERRED_LOOP_DELAY_MS = 50;

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
     * <p>While the agent owns timing, sleeps only until its next planned press, and returns
     * at once when no press is pending.</p>
     *
     * @param mean   the mean sleep time in milliseconds
     * @param stdDev the standard deviation in milliseconds
     */
    public static void sleep(long mean, long stdDev) {
        SessionPacing pacing = SessionPacing.current();
        if (pacing.isActive()) {
            Timing.sleep(pacing.nextPressInMs());
            return;
        }
        Timing.sleep(delay(mean, stdDev, 0, mean * 3));
    }

    /**
     * Returns a loop delay with variance and a 5% chance of a micro-break (2-5s).
     *
     * <p>While the agent owns timing, returns the time until its next planned press instead,
     * and never less than {@value #MIN_DEFERRED_LOOP_DELAY_MS} ms: the agent's plan already
     * carries the gap, the variance and the breaks.</p>
     *
     * @param baseMs         the base delay in milliseconds
     * @param varianceFactor multiplier for the variance (e.g., 0.3 = 30% of base)
     * @return the computed delay in milliseconds
     */
    public static int loopDelay(int baseMs, double varianceFactor) {
        SessionPacing pacing = SessionPacing.current();
        if (pacing.isActive()) {
            return deferredLoopDelay(pacing);
        }
        long variance = (long) (baseMs * varianceFactor);
        long delay = Math.max(baseMs / 2, Timing.gaussianRandom(baseMs, variance));

        if (ThreadLocalRandom.current().nextDouble() < MICRO_BREAK_PROBABILITY) {
            delay += ThreadLocalRandom.current().nextLong(MICRO_BREAK_MIN_MS, MICRO_BREAK_MAX_EXCLUSIVE_MS);
        }

        return (int) delay;
    }

    private static int deferredLoopDelay(SessionPacing pacing) {
        long wait = Math.max(MIN_DEFERRED_LOOP_DELAY_MS, pacing.nextPressInMs());
        return (int) Math.min(Integer.MAX_VALUE, wait);
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
