package com.botwithus.bot.api.util;

import java.util.function.BooleanSupplier;

/**
 * Polling-based condition utilities. Safe on virtual threads.
 */
public final class Conditions {

    private static final int DEFAULT_POLL_MS = 50;

    private Conditions() {}

    /**
     * Blocks until the condition returns {@code true} or the timeout expires.
     *
     * @param condition the condition to wait for
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if the condition was met, {@code false} if timed out
     */
    public static boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
        return waitUntil(condition, timeoutMs, DEFAULT_POLL_MS);
    }

    /**
     * Blocks until the condition returns {@code true} or the timeout expires,
     * polling at the specified interval.
     *
     * @param condition the condition to wait for
     * @param timeoutMs maximum time to wait in milliseconds
     * @param pollMs    polling interval in milliseconds
     * @return {@code true} if the condition was met, {@code false} if timed out
     */
    public static boolean waitUntil(BooleanSupplier condition, long timeoutMs, int pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * Blocks while the condition remains {@code true}, up to the timeout.
     *
     * @param condition the condition to wait on
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if the condition became {@code false}, {@code false} if timed out
     */
    public static boolean waitWhile(BooleanSupplier condition, long timeoutMs) {
        return waitUntil(() -> !condition.getAsBoolean(), timeoutMs);
    }

    /** Checks a condition once — convenience for readable script flow. */
    public static boolean verify(BooleanSupplier condition) {
        return condition.getAsBoolean();
    }
}
