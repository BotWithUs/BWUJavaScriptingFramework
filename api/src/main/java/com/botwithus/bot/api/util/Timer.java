package com.botwithus.bot.api.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * A countdown with a randomised length: each {@link #reset()} draws a new duration between
 * the minimum and maximum, inclusive, and starts counting it down. Useful for "do this every
 * 3 to 5 minutes" without a fixed, detectable period. Not thread-safe.
 *
 * <pre>{@code
 * Timer antiban = new Timer(180_000, 300_000);
 * ...
 * if (antiban.hasExpired()) {
 *     doSomethingHuman();
 *     antiban.reset();
 * }
 * }</pre>
 */
public final class Timer {

    private final long minMs;
    private final long maxMs;
    private final LongSupplier nanoClock;
    private final RandomGenerator random;
    private long durationMs;
    private long startNanos;

    /**
     * A started countdown of a random length between {@code minMs} and {@code maxMs}.
     *
     * @throws IllegalArgumentException when {@code minMs} is negative or above {@code maxMs}
     */
    public Timer(long minMs, long maxMs) {
        this(minMs, maxMs, System::nanoTime, ThreadLocalRandom.current());
    }

    /** A started countdown of exactly {@code durationMs}. */
    public Timer(long durationMs) {
        this(durationMs, durationMs);
    }

    /** For tests: a countdown on the given clock and random source. */
    Timer(long minMs, long maxMs, LongSupplier nanoClock, RandomGenerator random) {
        if (minMs < 0 || minMs > maxMs) {
            throw new IllegalArgumentException(
                    "need 0 <= minMs <= maxMs, got " + minMs + ".." + maxMs);
        }
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.nanoClock = nanoClock;
        this.random = random;
        reset();
    }

    /** Draws a new length and restarts the countdown from it. */
    public Timer reset() {
        durationMs = minMs == maxMs ? minMs : random.nextLong(minMs, maxMs + 1);
        startNanos = nanoClock.getAsLong();
        return this;
    }

    /** Whether the current countdown has run out. */
    public boolean hasExpired() {
        return remainingMs() == 0;
    }

    /** Milliseconds left on the current countdown; {@code 0} once it has run out. */
    public long remainingMs() {
        long elapsedMs = Duration.ofNanos(nanoClock.getAsLong() - startNanos).toMillis();
        return Math.max(0, durationMs - elapsedMs);
    }

    /** The length drawn for the current countdown. */
    public long durationMs() {
        return durationMs;
    }
}
