package com.botwithus.bot.api.runtime;

/**
 * How responsive a script runner is, as judged by the runtime's watchdog.
 *
 * <p>Orthogonal to {@link ScriptHealth}, which records <em>crashes</em>. A
 * script that has never thrown is {@link ScriptHealth#HEALTHY} and can still be
 * {@link #ABANDONED}: refusing to stop is not the same failure as throwing.</p>
 *
 * <p>{@link #LIVE} and {@link #STALLED} are recoverable — a runner moves freely
 * between them as {@code onLoop} calls start and finish. {@link #REVOKED} and
 * {@link #ABANDONED} are terminal: both mean the runtime has given up on the
 * thread ever draining and has stopped trusting it, and neither is ever left.</p>
 */
public enum Liveness {

    /** Looping normally, or parked between loops. */
    LIVE,

    /**
     * Still inside a single {@code onLoop} call past the watchdog's threshold.
     * Advisory when no stop is pending — a blocking walk legitimately parks
     * inside {@code onLoop} for minutes — and the first stage of the stop
     * escalation once one is.
     */
    STALLED,

    /**
     * Stop was requested, the thread did not drain, and the runtime has cut the
     * runner off from the game: every RPC it attempts from here on throws. Most
     * stuck scripts unwind on their own at this point, because they are usually
     * looping on an API call rather than spinning in pure computation. Terminal.
     */
    REVOKED,

    /**
     * Revoked and <em>still</em> alive. The thread cannot be killed — Java has
     * no {@code Thread.stop} — so it is quarantined instead: kept visible to the
     * user, and its classloader pinned so a reload never closes it underneath.
     * Terminal.
     */
    ABANDONED;

    /** {@code true} for the states a runner can never leave. */
    public boolean isTerminal() {
        return this == REVOKED || this == ABANDONED;
    }
}
