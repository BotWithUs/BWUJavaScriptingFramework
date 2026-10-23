package com.botwithus.bot.cli.sdnlive;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition the way a person watching the screen would: until it holds or time is up. */
final class Waits {

    private static final Duration POLL = Duration.ofMillis(100);

    private Waits() {
    }

    /** True once {@code condition} holds, false if it still does not after {@code timeout}. */
    static boolean until(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
