package com.botwithus.bot.api.runtime;

/**
 * A break the agent's session layer has planned: the client will sit idle, with the
 * action that carries the break held until it is over.
 *
 * <p>Read-only. A script cannot move or cancel a break, and the agent never skips one on
 * its own; a script can only see one coming and decide what to do about it, for example
 * bank before it rather than stall half way through a trip.</p>
 *
 * @param carrier    which action the break is attached to
 * @param startsInMs milliseconds until the break starts, measured when the plan was read;
 *                   {@code 0} when it has started, and always {@code 0} for
 *                   {@link Carrier#NEXT_ACTION}, whose break starts when that action is
 *                   dispatched
 * @param lengthMs   milliseconds of break still to run from its start (or from now, once it
 *                   has started); always positive
 */
public record PlannedBreak(Carrier carrier, long startsInMs, long lengthMs) {

    /** Which action a planned break is attached to. */
    public enum Carrier {
        /** An action is already queued and held; the break runs inside that hold. */
        HELD_ACTION,
        /**
         * The agent plans the next action ahead, right after the previous press. The next
         * action the script dispatches will be held for this break before it is pressed.
         */
        NEXT_ACTION
    }

    public PlannedBreak {
        if (carrier == null) {
            throw new IllegalArgumentException("carrier must not be null");
        }
        if (startsInMs < 0) {
            throw new IllegalArgumentException("startsInMs must be >= 0: " + startsInMs);
        }
        if (lengthMs <= 0) {
            throw new IllegalArgumentException("lengthMs must be > 0: " + lengthMs);
        }
    }

    /** Milliseconds from when the plan was read until the break is over. */
    public long endsInMs() {
        return startsInMs + lengthMs;
    }
}
