package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tick-aware timing utilities for bot scripts.
 * All delays are in milliseconds. Safe on virtual threads.
 */
public final class Timing {

    public static final int TICK_MS = 600;

    private Timing() {}

    /**
     * Sleeps for the specified duration. Restores the interrupt flag if interrupted.
     *
     * @param ms duration in milliseconds; values &lt;= 0 return immediately
     */
    public static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sleeps for a uniformly random duration between {@code minMs} and {@code maxMs}.
     *
     * @param minMs minimum sleep duration in milliseconds
     * @param maxMs maximum sleep duration in milliseconds
     */
    public static void sleepRandom(long minMs, long maxMs) {
        sleep(random(minMs, maxMs));
    }

    /** Inclusive on both ends. */
    public static long random(long min, long max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** Inclusive on both ends. */
    public static int random(int min, int max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** ~600ms with small random jitter. */
    public static void sleepTick() {
        sleepRandom(580, 650);
    }

    /** 150–300ms. */
    public static void shortDelay() {
        sleepRandom(150, 300);
    }

    /** 300–600ms. */
    public static void mediumDelay() {
        sleepRandom(300, 600);
    }

    /** 600–1200ms. */
    public static void longDelay() {
        sleepRandom(600, 1200);
    }

    /**
     * Converts game ticks to milliseconds.
     *
     * @param ticks the number of game ticks
     * @return the equivalent duration in milliseconds
     */
    public static long ticksToMs(int ticks) {
        return (long) ticks * TICK_MS;
    }

    /** Returns a gaussian-distributed random value around a mean. */
    public static long gaussianRandom(long mean, long stdDev) {
        long value = (long) (mean + ThreadLocalRandom.current().nextGaussian() * stdDev);
        return Math.max(0, value);
    }

    /** Sleep for a gaussian-distributed random duration. */
    public static void sleepGaussian(long meanMs, long stdDevMs) {
        sleep(gaussianRandom(meanMs, stdDevMs));
    }
}
