package com.botwithus.bot.core.alerts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock that runs steadily slower than real time and never goes backwards.
 *
 * <p>It reads {@link System#nanoTime()} relative to an origin taken at
 * construction and divides the elapsed time by a slowdown factor, so a factor
 * of two makes it run at half speed. Two properties make it useful for testing
 * code that waits on an injected {@link Clock}:</p>
 *
 * <ul>
 *   <li><b>It is monotonic.</b> A wall clock can be stepped backwards between
 *       two reads; this one cannot, so "ran no earlier than due" is a claim
 *       about the code under test and not about the host's time service.</li>
 *   <li><b>It disagrees with every real clock.</b> Code that quietly waits on
 *       {@code Instant.now()} or {@code System.nanoTime()} instead of the clock
 *       it was given finishes early by this clock's reckoning, which a test can
 *       see. A clock running at or above real speed would hide that.</li>
 * </ul>
 *
 * <p>{@link #withZone(ZoneId)} returns a clock on the same timeline: the zone
 * changes, the instants do not.</p>
 */
final class SlowMonotonicClock extends Clock {

    /** Half speed, the default rate. */
    static final int DEFAULT_SLOWDOWN = 2;

    private static final int MIN_SLOWDOWN = 2;

    private final Instant base;
    private final long originNanos;
    private final int slowdown;
    private final ZoneId zone;

    private SlowMonotonicClock(Instant base, long originNanos, int slowdown, ZoneId zone) {
        this.base = base;
        this.originNanos = originNanos;
        this.slowdown = slowdown;
        this.zone = zone;
    }

    /** A half-speed UTC clock that reads {@code base} now. */
    static SlowMonotonicClock startingAt(Instant base) {
        return startingAt(base, DEFAULT_SLOWDOWN);
    }

    /**
     * A UTC clock that reads {@code base} now and then advances one nanosecond
     * for every {@code slowdown} real ones.
     *
     * @throws IllegalArgumentException if {@code slowdown} is below two, which
     *         would not be slower than real time
     */
    static SlowMonotonicClock startingAt(Instant base, int slowdown) {
        Objects.requireNonNull(base, "base");
        if (slowdown < MIN_SLOWDOWN) {
            throw new IllegalArgumentException("slowdown must be at least " + MIN_SLOWDOWN
                    + " to run slower than real time, was " + slowdown);
        }
        return new SlowMonotonicClock(base, System.nanoTime(), slowdown, ZoneOffset.UTC);
    }

    int slowdown() {
        return slowdown;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        Objects.requireNonNull(newZone, "zone");
        if (newZone.equals(zone)) {
            return this;
        }
        return new SlowMonotonicClock(base, originNanos, slowdown, newZone);
    }

    @Override
    public Instant instant() {
        return base.plusNanos((System.nanoTime() - originNanos) / slowdown);
    }
}
