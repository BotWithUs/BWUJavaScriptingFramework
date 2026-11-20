package com.botwithus.bot.api.util;

import com.botwithus.bot.api.runtime.ScriptPause;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The polling engine behind {@link Timing}'s condition waits. It owns no clock and no
 * thread of its own: time, sleeping, the stop signal and the pause signal are handed in,
 * which is what lets the waits be tested without sleeping and lets {@link Timing} bind the
 * real ones.
 *
 * <p>Every wait checks the stop signal before each poll, so a stop request ends it within
 * one poll interval. While the pause signal holds, a wait does not test its condition and
 * does not spend its budget: wall time spent paused moves the deadline out, and server ticks
 * that pass while paused are not counted.</p>
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
    private final BooleanSupplier paused;

    /** An engine that never pauses. */
    ConditionWait(LongSupplier nanoClock, LongConsumer sleeper, BooleanSupplier stopRequested) {
        this(nanoClock, sleeper, stopRequested, () -> false);
    }

    /**
     * @param nanoClock     monotonic time in nanoseconds
     * @param sleeper       sleeps for the given milliseconds
     * @param stopRequested true once the waiting script has been asked to stop
     * @param paused        true while the waiting script is paused
     */
    ConditionWait(LongSupplier nanoClock, LongConsumer sleeper, BooleanSupplier stopRequested,
                  BooleanSupplier paused) {
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.stopRequested = stopRequested;
        this.paused = paused;
    }

    /**
     * The engine bound to the real clock, {@link Timing#sleep}, thread interruption and the
     * calling script's {@link ScriptPause} (none off a script thread).
     */
    static ConditionWait onCurrentThread() {
        Optional<ScriptPause> pause = ScriptPause.current();
        BooleanSupplier paused = pause.isPresent() ? pause.get()::isPaused : () -> false;
        return new ConditionWait(System::nanoTime, Timing::sleep,
                () -> Thread.currentThread().isInterrupted(), paused);
    }

    /**
     * Polls until {@code condition} holds, {@code timeoutMs} passes, or a stop is requested.
     *
     * @return {@code true} only when the condition held
     */
    boolean until(BooleanSupplier condition, long timeoutMs) {
        long deadline = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        while (!stopRequested.getAsBoolean()) {
            long heldNanos = holdWhilePaused();
            if (heldNanos > 0) {
                deadline += heldNanos;
                continue;
            }
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
        return runTicks(serverTick, condition, maxTicks, false);
    }

    /**
     * Waits until the server tick has advanced {@code ticks} past where it was at the start.
     *
     * @return {@code true} when the ticks passed; {@code false} on a stop request or when the
     *         tick clock stalled
     */
    boolean ticks(IntSupplier serverTick, int ticks) {
        return runTicks(serverTick, () -> false, ticks, true);
    }

    /**
     * The loop behind both tick waits: {@code true} when {@code condition} holds, and
     * {@code budgetResult} once {@code maxTicks} have passed. Ticks that pass while paused
     * move the start forward, and wall time spent paused moves the stall cap out. A
     * {@link #ticks} wait gets one tick more of wall cap, as it always has.
     */
    private boolean runTicks(IntSupplier serverTick, BooleanSupplier condition, int maxTicks,
                             boolean budgetResult) {
        int start = serverTick.getAsInt();
        long wallDeadline = nanoClock.getAsLong()
                + wallCapNanos(budgetResult ? maxTicks + 1 : maxTicks);
        while (!stopRequested.getAsBoolean()) {
            int beforeHold = serverTick.getAsInt();
            long heldNanos = holdWhilePaused();
            if (heldNanos > 0) {
                start += ticksElapsed(beforeHold, serverTick.getAsInt());
                wallDeadline += heldNanos;
                continue;
            }
            if (condition.getAsBoolean()) {
                return true;
            }
            if (ticksElapsed(start, serverTick.getAsInt()) >= maxTicks) {
                return budgetResult;
            }
            if (nanoClock.getAsLong() - wallDeadline >= 0) {
                return false;
            }
            sleeper.accept(POLL_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Sleeps in poll intervals while paused and not stopping.
     *
     * @return the nanoseconds spent paused, at least 1 when it paused; {@code 0} when not
     */
    private long holdWhilePaused() {
        if (!paused.getAsBoolean()) {
            return 0;
        }
        long start = nanoClock.getAsLong();
        while (paused.getAsBoolean() && !stopRequested.getAsBoolean()) {
            sleeper.accept(POLL_INTERVAL_MS);
        }
        return Math.max(1, nanoClock.getAsLong() - start);
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
