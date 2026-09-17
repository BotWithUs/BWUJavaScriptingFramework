package com.botwithus.bot.api.runtime;

import java.util.Optional;

/**
 * Immutable snapshot of a script's failure history. Held atomically by the
 * runtime; mutated only by replacing the whole record via {@link #withCrash}.
 *
 * <p>{@link #lastCrash} is empty when the script has never thrown; otherwise
 * it holds the most recent failure. {@link #totalCrashes} counts every
 * failure observed since the runner was constructed (not just the current
 * lifetime, since the same {@code ScriptRunner} can be restarted).</p>
 */
public record ScriptHealth(Optional<LastCrash> lastCrash, long totalCrashes) {

    /** Sentinel for a never-crashed script. */
    public static final ScriptHealth HEALTHY = new ScriptHealth(Optional.empty(), 0L);

    /**
     * Returns a new {@code ScriptHealth} with the given crash recorded and
     * {@link #totalCrashes} incremented by one. The receiver is unmodified.
     */
    public ScriptHealth withCrash(LastCrash crash) {
        return new ScriptHealth(Optional.of(crash), totalCrashes + 1);
    }
}
