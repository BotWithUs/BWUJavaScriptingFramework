package com.botwithus.bot.core.rpc;

/**
 * Configures retry behavior for RPC calls.
 */
public record RetryPolicy(int maxRetries, long initialDelayMs, double backoffMultiplier, long maxDelayMs) {

    public static final RetryPolicy NONE = new RetryPolicy(0, 0, 1.0, 0);
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 100, 2.0, 5000);

    public long delayForAttempt(int attempt) {
        if (attempt <= 0) return 0;
        long delay = (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1));
        return Math.min(delay, maxDelayMs);
    }
}
