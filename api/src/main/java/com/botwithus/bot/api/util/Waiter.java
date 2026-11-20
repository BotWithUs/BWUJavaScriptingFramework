package com.botwithus.bot.api.util;

import java.util.function.BooleanSupplier;

/**
 * Waits for a condition: {@link Timing#delayUntil} as a value, so a facade that waits can be
 * handed a different one (a test's, which never sleeps).
 */
@FunctionalInterface
public interface Waiter {

    /** {@link Timing#delayUntil}: polls on the calling thread. */
    Waiter TIMING = Timing::delayUntil;

    /**
     * Waits until {@code condition} holds or {@code timeoutMs} passes.
     *
     * @return {@code true} only when the condition held
     */
    boolean until(BooleanSupplier condition, long timeoutMs);
}
