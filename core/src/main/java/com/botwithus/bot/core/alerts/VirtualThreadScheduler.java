package com.botwithus.bot.core.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link AlertScheduler} that gives each task its own virtual thread, which
 * waits for the moment against {@link Clock} time.
 *
 * <p>The thread naps at most {@link #LONGEST_NAP} at a time and reads the clock
 * again after each nap, so a PC that sleeps through the moment, or a clock that is
 * set forward, runs the task soon after waking rather than hours late.</p>
 *
 * <p>{@link Scheduled#cancel()} only stops a task that is still waiting. A task
 * that has started runs to the end: cancelling never interrupts a send.</p>
 */
public final class VirtualThreadScheduler implements AlertScheduler {

    /** The longest single wait before the clock is read again. */
    static final Duration LONGEST_NAP = Duration.ofSeconds(30);

    private static final String THREAD_NAME = "alerts-timer";

    private enum State { WAITING, RUNNING, CANCELLED }

    private final Clock clock;

    public VirtualThreadScheduler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Scheduled schedule(Instant at, Runnable task) {
        AtomicReference<State> state = new AtomicReference<>(State.WAITING);
        Thread thread = Thread.ofVirtual().name(THREAD_NAME).start(() -> {
            if (waitUntil(at) && state.compareAndSet(State.WAITING, State.RUNNING)) {
                task.run();
            }
        });
        return () -> {
            if (state.compareAndSet(State.WAITING, State.CANCELLED)) {
                thread.interrupt();
            }
        };
    }

    /** Waits until {@code at}; {@code false} if interrupted (cancelled) first. */
    private boolean waitUntil(Instant at) {
        try {
            Duration remaining = Duration.between(clock.instant(), at);
            while (remaining.isPositive()) {
                Thread.sleep(remaining.compareTo(LONGEST_NAP) < 0 ? remaining : LONGEST_NAP);
                remaining = Duration.between(clock.instant(), at);
            }
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
