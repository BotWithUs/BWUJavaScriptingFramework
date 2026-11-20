package com.botwithus.bot.api.combat;

/**
 * A cooldown window in client cycles ({@code GameSnapshot.gameCycle()} units), as the
 * client's {@code ..._START_CLIENT} / {@code ..._END_CLIENT} varcs hold it.
 *
 * <p>Client cycles, not server ticks: compare {@link #endCycle()} with
 * {@code snapshot.gameCycle()}, never with {@code serverTick()} or {@code publishSeq()}.</p>
 *
 * @param startCycle the cycle the cooldown started on
 * @param endCycle   the cycle it ends on
 * @param isKnown    false for {@link #UNKNOWN}: the window could not be read, which is not
 *                   the same as "ready"
 */
public record Cooldown(int startCycle, int endCycle, boolean isKnown) {

    /** Milliseconds per client cycle. */
    public static final int CLIENT_CYCLE_MS = 20;

    /** A cooldown that could not be read. */
    public static final Cooldown UNKNOWN = new Cooldown(0, 0, false);

    /** A known window. */
    public static Cooldown of(int startCycle, int endCycle) {
        return new Cooldown(startCycle, endCycle, true);
    }

    /**
     * Cycles left at {@code nowCycle}; {@code 0} once it has ended. {@code -1} when
     * {@link #isKnown()} is false.
     */
    public int remainingCycles(int nowCycle) {
        if (!isKnown) {
            return -1;
        }
        return Math.max(0, endCycle - nowCycle);
    }

    /** {@link #remainingCycles(int)} in milliseconds; {@code -1} when unknown. */
    public long remainingMs(int nowCycle) {
        int cycles = remainingCycles(nowCycle);
        return cycles < 0 ? -1 : (long) cycles * CLIENT_CYCLE_MS;
    }

    /** True when the window is known and has ended at {@code nowCycle}. */
    public boolean isReady(int nowCycle) {
        return isKnown && nowCycle >= endCycle;
    }
}
