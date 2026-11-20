package com.botwithus.bot.api.util;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A pausable elapsed-time counter on the monotonic clock. Not thread-safe: one script thread
 * owns a stopwatch.
 *
 * <pre>{@code
 * Stopwatch runtime = Stopwatch.started();
 * ...
 * runtime.pause();              // e.g. during a break
 * runtime.resume();
 * log.info("ran {}", Durations.format(runtime.elapsed()));
 * }</pre>
 */
public final class Stopwatch {

    private final LongSupplier nanoClock;
    private long accumulatedNanos;
    private long runningSinceNanos;
    private boolean isRunning;

    /** A stopwatch that has not started; {@link #elapsed()} reads zero until {@link #start()}. */
    public Stopwatch() {
        this(System::nanoTime);
    }

    /** For tests: a stopwatch on the given nanosecond clock. */
    Stopwatch(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** A stopwatch that is already running. */
    public static Stopwatch started() {
        Stopwatch watch = new Stopwatch();
        watch.start();
        return watch;
    }

    /** For tests: a running stopwatch on the given clock. */
    static Stopwatch started(LongSupplier nanoClock) {
        Stopwatch watch = new Stopwatch(nanoClock);
        watch.start();
        return watch;
    }

    /** Starts counting. Same as {@link #resume()}; a no-op while running. */
    public Stopwatch start() {
        if (!isRunning) {
            runningSinceNanos = nanoClock.getAsLong();
            isRunning = true;
        }
        return this;
    }

    /** Continues counting after {@link #pause()}; a no-op while running. */
    public Stopwatch resume() {
        return start();
    }

    /** Stops counting and keeps the time so far; a no-op while paused. */
    public Stopwatch pause() {
        if (isRunning) {
            accumulatedNanos += nanoClock.getAsLong() - runningSinceNanos;
            isRunning = false;
        }
        return this;
    }

    /** Zeroes the elapsed time. A running stopwatch keeps running from zero. */
    public Stopwatch reset() {
        accumulatedNanos = 0;
        runningSinceNanos = nanoClock.getAsLong();
        return this;
    }

    /** Whether the stopwatch is counting. */
    public boolean isRunning() {
        return isRunning;
    }

    /** Time counted so far, excluding paused spans. */
    public Duration elapsed() {
        long running = isRunning ? nanoClock.getAsLong() - runningSinceNanos : 0;
        return Duration.ofNanos(accumulatedNanos + running);
    }

    /** {@link #elapsed()} in milliseconds. */
    public long elapsedMs() {
        return elapsed().toMillis();
    }
}
