package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Tick-aware timing utilities for bot scripts.
 * All delays are in milliseconds. Safe on virtual threads.
 *
 * <h2>Condition waits</h2>
 * {@link #delayUntil}, {@link #delayWhile}, {@link #delayUntilTicks} and {@link #delayTicks}
 * poll a condition every {@value ConditionWait#POLL_INTERVAL_MS} ms on the calling thread and
 * say whether it was met. They end early, returning {@code false}, when the script is asked to
 * stop: the runtime interrupts a stopping script's thread, and every wait checks for that
 * before each poll, so a stop is honoured within one poll interval rather than at the
 * timeout. A {@code false} therefore means "not met" — timed out <em>or</em> stopping; check
 * {@link com.botwithus.bot.api.ScriptContext#isStopRequested()} when the difference matters.
 *
 * <pre>{@code
 * if (!Timing.delayUntil(bank::isOpen, 5_000)) {
 *     return ctx.isStopRequested() ? -1 : RETRY_MS;
 * }
 * }</pre>
 *
 * <p>The tick forms count the <b>server</b> tick ({@link GameSnapshot#serverTick()}, 600 ms),
 * never {@code publishSeq()}, which advances about thirty times as fast.</p>
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
        if (ms <= 0) {
            return;
        }
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
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** Inclusive on both ends. */
    public static int random(int min, int max) {
        if (min >= max) {
            return min;
        }
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

    // ---------------------------------------------------------------- Condition waits

    /**
     * Waits until {@code condition} holds, polling it on this thread.
     *
     * @param condition checked once immediately, then every poll interval
     * @param timeoutMs longest wait in milliseconds; {@code <= 0} checks once
     * @return {@code true} when the condition held; {@code false} on timeout or stop request
     */
    public static boolean delayUntil(BooleanSupplier condition, long timeoutMs) {
        return ConditionWait.onCurrentThread().until(condition, timeoutMs);
    }

    /**
     * Waits while {@code condition} holds — until it stops holding.
     *
     * @param condition checked once immediately, then every poll interval
     * @param timeoutMs longest wait in milliseconds; {@code <= 0} checks once
     * @return {@code true} when the condition stopped holding; {@code false} when it still held
     *         at the timeout, or a stop was requested
     */
    public static boolean delayWhile(BooleanSupplier condition, long timeoutMs) {
        return delayUntil(() -> !condition.getAsBoolean(), timeoutMs);
    }

    /**
     * Waits until {@code condition} holds, for at most {@code maxTicks} server ticks.
     *
     * <p>If no server tick is advancing (logged out, no snapshot published) the wait still
     * ends, after {@value ConditionWait#STALLED_CLOCK_FACTOR} tick lengths of wall time per
     * tick asked for, so a stalled clock never hangs a script.</p>
     *
     * @param api       source of the server tick
     * @param condition checked once immediately, then every poll interval
     * @param maxTicks  longest wait in server ticks
     * @return {@code true} when the condition held; {@code false} otherwise
     */
    public static boolean delayUntilTicks(GameAPI api, BooleanSupplier condition, int maxTicks) {
        return ConditionWait.onCurrentThread().untilTicks(() -> serverTick(api), condition, maxTicks);
    }

    /**
     * Waits a random whole number of server ticks between {@code minTicks} and
     * {@code maxTicks}, inclusive, counted on the server tick rather than the wall clock.
     *
     * @return {@code true} when the ticks passed; {@code false} on a stop request or when the
     *         server tick stopped advancing
     */
    public static boolean delayTicks(GameAPI api, int minTicks, int maxTicks) {
        return ConditionWait.onCurrentThread().ticks(() -> serverTick(api), random(minTicks, maxTicks));
    }

    private static int serverTick(GameAPI api) {
        GameSnapshot snapshot = api.snapshot();
        return snapshot == null ? ConditionWait.NO_TICK : snapshot.serverTick();
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
