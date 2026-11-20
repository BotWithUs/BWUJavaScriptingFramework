package com.botwithus.bot.api.util;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The polling engine behind {@link Timing}'s condition waits. It owns no clock and no
 * thread of its own: time, sleeping and the stop signal are handed in, which is what lets
 * the waits be tested without sleeping and lets {@link Timing} bind the real ones.
 *
 * <p>Every wait checks the stop signal before each poll, so a stop request ends it within
 * one poll interval.</p>
 */
final class ConditionWait {

    /** How long a wait sleeps between two checks of its condition. */
    static final long POLL_INTERVAL_MS = 50;

    /**
     * How many tick lengths of wall time a tick-driven wait allows per tick it was asked to
     * wait, before deciding the server tick has stopped advancing (logged out, no snapshot).
     */
    static final int STALLED_CLOCK_FACTOR = 2;

    /** {@link IntSupplier} answer for "no server tick is available right now". */
    static final int NO_TICK = Integer.MIN_VALUE;

    private final LongSupplier nanoClock;
    private final LongConsumer sleeper;
    private final BooleanSupplier stopRequested;

    /**
     * @param nanoClock     monotonic time in nanoseconds
     * @param sleeper       sleeps for the given milliseconds
     * @param stopRequested true once the waiting script has been asked to stop
     */
    ConditionWait(LongSupplier nanoClock, LongConsumer sleeper, BooleanSupplier stopRequested) {
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.stopRequested = stopRequested;
    }

    /** The engine bound to the real clock, {@link Timing#sleep} and thread interruption. */
    static ConditionWait onCurrentThread() {
        return new ConditionWait(System::nanoTime, Timing::sleep,
                () -> Thread.currentThread().isInterrupted());
    }

    /**
     * Polls until {@code condition} holds, {@code timeoutMs} passes, or a stop is requested.
     *
     * @return {@code true} only when the condition held
     */
    boolean until(BooleanSupplier condition, long timeoutMs) {
        long deadline = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        while (!stopRequested.getAsBoolean()) {
            if (condition.getAsBoolean()) {
                return true;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - nanoClock.getAsLong());
            if (remainingMs <= 0) {
                return false;
            }
            sleeper.accept(Math.min(POLL_INTERVAL_MS, remainingMs));
        }
        return false;
    }

    /**
     * Polls until {@code condition} holds, the server tick has advanced {@code maxTicks} past
     * where it was at the start, or a stop is requested. If the tick stops advancing the wait
     * also ends, after {@link #STALLED_CLOCK_FACTOR} tick lengths of wall time per tick.
     *
     * @param serverTick the current server tick, or {@link #NO_TICK} when none is published
     * @return {@code true} only when the condition held
     */
    boolean untilTicks(IntSupplier serverTick, BooleanSupplier condition, int maxTicks) {
        int start = serverTick.getAsInt();
        long wallDeadline = nanoClock.getAsLong() + wallCapNanos(maxTicks);
        while (!stopRequested.getAsBoolean()) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (ticksElapsed(start, serverTick.getAsInt()) >= maxTicks
                    || nanoClock.getAsLong() - wallDeadline >= 0) {
                return false;
            }
            sleeper.accept(POLL_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Waits until the server tick has advanced {@code ticks} past where it was at the start.
     *
     * @return {@code true} when the ticks passed; {@code false} on a stop request or when the
     *         tick clock stalled
     */
    boolean ticks(IntSupplier serverTick, int ticks) {
        int start = serverTick.getAsInt();
        BooleanSupplier passed = () -> ticksElapsed(start, serverTick.getAsInt()) >= ticks;
        return untilTicks(serverTick, passed, ticks + 1);
    }

    /**
     * Ticks between two readings; {@code 0} when either is {@link #NO_TICK}. Subtraction
     * rather than comparison keeps it right across an {@code int} wrap.
     */
    private static int ticksElapsed(int start, int now) {
        if (start == NO_TICK || now == NO_TICK) {
            return 0;
        }
        return now - start;
    }

    private static long wallCapNanos(int maxTicks) {
        long ms = Timing.ticksToMs(Math.max(1, maxTicks)) * STALLED_CLOCK_FACTOR;
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }
}
