package com.botwithus.bot.core.rpc;

/**
 * Backoff policy for {@link ReconnectController}: exponential, clamped to
 * {@link #maxDelayMs()}, bounded by {@link #maxAttempts()}.
 *
 * <p>Same shape as {@link RetryPolicy} but with attempt-budget semantics
 * appropriate for connection recovery (the default reconnects "forever"
 * since pipe drops in the field are typically transient).</p>
 */
public record ReconnectPolicy(int maxAttempts, long initialDelayMs,
                              double backoffMultiplier, long maxDelayMs) {

    /**
     * Production default: practically-unbounded attempts, 500ms initial delay
     * doubling up to a 15s ceiling. A game-client crash that takes 30s to
     * restart will trigger ~6 attempts before the pipe is back.
     */
    public static final ReconnectPolicy DEFAULT =
            new ReconnectPolicy(Integer.MAX_VALUE, 500L, 2.0, 15_000L);

    public ReconnectPolicy {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("initialDelayMs must be >= 0");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
        }
    }

    /**
     * Returns the wait in milliseconds before the given {@code attempt}
     * (1-indexed). Exponential: {@code initialDelayMs * multiplier^(attempt-1)},
     * clamped to {@link #maxDelayMs()}. Attempt 0 or below yields 0.
     */
    public long delayForAttempt(int attempt) {
        if (attempt <= 0) {
            return 0L;
        }
        double scaled = initialDelayMs * Math.pow(backoffMultiplier, attempt - 1);
        if (scaled >= maxDelayMs) {
            return maxDelayMs;
        }
        return (long) scaled;
    }
}
