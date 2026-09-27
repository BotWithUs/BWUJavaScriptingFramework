package com.botwithus.bot.core.rpc;

import java.util.OptionalInt;

/**
 * Backoff policy for {@link ReconnectController}: exponential, clamped to
 * {@link #maxDelayMs()}, bounded by {@link #maxAttempts()} unless that is
 * {@link #UNLIMITED}.
 *
 * <p>Same shape as {@link RetryPolicy} but with attempt-budget semantics
 * appropriate for connection recovery (the default reconnects "forever"
 * since pipe drops in the field are typically transient).</p>
 *
 * @param maxAttempts attempts per recovery before giving up, or
 *                    {@link #UNLIMITED} to keep trying until the pipe is
 *                    back or the client is gone
 */
public record ReconnectPolicy(int maxAttempts, long initialDelayMs,
                              double backoffMultiplier, long maxDelayMs) {

    /**
     * The {@link #maxAttempts()} that sets no budget. Zero rather than
     * {@code Integer.MAX_VALUE}, so "no cap" is a value a user can type and a
     * UI can recognise, instead of a very large number it prints.
     */
    public static final int UNLIMITED = 0;

    private static final double MIN_BACKOFF_MULTIPLIER = 1.0;

    /**
     * Production default: unlimited attempts, 500ms initial delay doubling up
     * to a 15s ceiling. An agent-side client drop that leaves the game running
     * takes ~6 attempts before the pipe is back.
     *
     * <p>The attempt budget is deliberately not the thing that stops recovery.
     * It used to be the only bound, which meant a game that restarted under a
     * new pid — a new {@code BotWithUs_<pid>} name — was retried forever
     * against a name that could never exist again. Termination is now
     * {@link SamePidPipeResolver}'s job: it stops as soon as the original
     * process is gone, because a different pid cannot be adopted without
     * rebuilding the shared-memory mapping the connection is built on.</p>
     */
    public static final ReconnectPolicy DEFAULT =
            new ReconnectPolicy(UNLIMITED, 500L, 2.0, 15_000L);

    public ReconnectPolicy {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("initialDelayMs must be >= 0");
        }
        if (backoffMultiplier < MIN_BACKOFF_MULTIPLIER) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
        }
    }

    /**
     * Builds a policy from values that were never checked against each other,
     * such as independently edited settings, pulling each into range instead
     * of refusing: a negative budget becomes {@link #UNLIMITED}, one past
     * {@code int} range becomes {@code Integer.MAX_VALUE}, negative delays
     * become zero, a multiplier below one becomes one, and a longest wait
     * shorter than the first wait is raised to it.
     */
    public static ReconnectPolicy clamped(long maxAttempts, long initialDelayMs,
                                          double backoffMultiplier, long maxDelayMs) {
        int attempts = Math.clamp(maxAttempts, UNLIMITED, Integer.MAX_VALUE);
        long initial = Math.max(0L, initialDelayMs);
        double backoff = Math.max(MIN_BACKOFF_MULTIPLIER, backoffMultiplier);
        return new ReconnectPolicy(attempts, initial, backoff, Math.max(initial, maxDelayMs));
    }

    /** The attempt budget, or empty when the policy is {@link #UNLIMITED}. */
    public OptionalInt attemptLimit() {
        return maxAttempts == UNLIMITED ? OptionalInt.empty() : OptionalInt.of(maxAttempts);
    }

    /** Whether the 1-indexed {@code attempt} is within the budget. */
    public boolean allowsAttempt(int attempt) {
        return maxAttempts == UNLIMITED || attempt <= maxAttempts;
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
