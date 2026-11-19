package com.botwithus.bot.core.impl;

/**
 * Maps the agent's plugin-UI handle numbers to the ones scripts see, so a handle
 * from before a connection reset can never act on a component made after it.
 *
 * <p>The agent's handles are a counter that is never reused within one agent, but
 * a re-injected agent starts counting again, so after a reconnect the number a
 * stale {@code Canvas} holds can name a brand-new component, possibly another
 * script's. A script-visible handle is therefore the agent's number with the
 * connection's epoch in its top bits. Before the first reset the epoch is
 * {@code 0} and the two numbers are equal. A handle whose epoch is not the current
 * one is refused without reaching the agent.</p>
 *
 * <p>The epoch is 16 bits and wraps, so a handle kept across 65,536 resets could in
 * principle be current again; the agent's counter would also have to reach the
 * same number. Agent handles must fit in the low 48 bits, which a counter will not
 * leave in practice; one that does is a protocol error, raised as such.</p>
 */
final class UiHandleSpace {

    static final int EPOCH_SHIFT = 48;
    static final long AGENT_MASK = (1L << EPOCH_SHIFT) - 1;
    private static final long EPOCH_MASK = 0xFFFFL;

    private volatile long epoch;

    /** The script-visible handle for an agent handle in the current epoch; {@code 0} stays {@code 0}. */
    long toHost(long agentHandle) {
        return compose(epoch, agentHandle);
    }

    /** The script-visible handle for an agent handle in {@code inEpoch}. */
    static long compose(long inEpoch, long agentHandle) {
        if (agentHandle == 0) {
            return 0;
        }
        if ((agentHandle & ~AGENT_MASK) != 0) {
            throw new IllegalStateException("agent handle " + Long.toUnsignedString(agentHandle)
                    + " does not fit in " + EPOCH_SHIFT + " bits");
        }
        return (inEpoch << EPOCH_SHIFT) | agentHandle;
    }

    /** Whether {@code hostHandle} was issued in the current epoch. */
    boolean isCurrent(long hostHandle) {
        return (hostHandle >>> EPOCH_SHIFT) == epoch;
    }

    /** The agent's number inside a script-visible handle. */
    static long agentOf(long hostHandle) {
        return hostHandle & AGENT_MASK;
    }

    /** Starts a new epoch and returns the one that ended. */
    synchronized long advance() {
        long ended = epoch;
        epoch = (ended + 1) & EPOCH_MASK;
        return ended;
    }
}
