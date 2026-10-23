package com.botwithus.bot.core.alerts;

import java.time.Instant;

/**
 * Runs a task once at a given moment. {@link VirtualThreadScheduler} in the host;
 * a hand-cranked fake in tests, so a burst window or a daily summary is tested
 * without waiting.
 */
@FunctionalInterface
public interface AlertScheduler {

    /** A task that has been scheduled. */
    @FunctionalInterface
    interface Scheduled {

        /** Stops the task from running, if it has not started. */
        void cancel();
    }

    /** Runs {@code task} once, at {@code at} or as soon after as the scheduler can. */
    Scheduled schedule(Instant at, Runnable task);
}
