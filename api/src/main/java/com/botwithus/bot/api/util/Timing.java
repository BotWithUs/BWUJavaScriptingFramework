package com.botwithus.bot.api.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tick-aware timing utilities for bot scripts.
 * All delays are in milliseconds. Safe on virtual threads.
 */
public final class Timing {

    public static final int TICK_MS = 600;

    private Timing() {}

    public static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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

    public static long ticksToMs(int ticks) {
        return (long) ticks * TICK_MS;
    }
}
