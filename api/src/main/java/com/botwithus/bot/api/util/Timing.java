package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tick-aware timing utilities for bot scripts.
 * All delays are in milliseconds. Safe on virtual threads.
 */
public final class Timing {

    /** Duration of one game tick in milliseconds. */
    public static final int TICK_MS = 600;

    private Timing() {}

    /**
     * Sleeps for the given number of milliseconds.
     *
     * @param ms milliseconds to sleep
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
     * Sleeps for a random duration between {@code minMs} and {@code maxMs} (inclusive).
     *
     * @param minMs minimum sleep time in milliseconds
     * @param maxMs maximum sleep time in milliseconds
     */
    public static void sleepRandom(long minMs, long maxMs) {
        sleep(random(minMs, maxMs));
    }

    /**
     * Returns a random long between {@code min} and {@code max} (inclusive).
     *
     * @param min the minimum value
     * @param max the maximum value
     * @return a random value in [min, max]
     */
    public static long random(long min, long max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /**
     * Returns a random int between {@code min} and {@code max} (inclusive).
     *
     * @param min the minimum value
     * @param max the maximum value
     * @return a random value in [min, max]
     */
    public static int random(int min, int max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Sleeps for approximately one game tick (600ms) with a small random jitter.
     */
    public static void sleepTick() {
        sleepRandom(580, 650);
    }

    /**
     * A short delay: 150–300ms. Useful between rapid interactions.
     */
    public static void shortDelay() {
        sleepRandom(150, 300);
    }

    /**
     * A medium delay: 300–600ms. Roughly half a game tick.
     */
    public static void mediumDelay() {
        sleepRandom(300, 600);
    }

    /**
     * A long delay: 600–1200ms. One to two game ticks.
     */
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
}
