package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.runtime.Liveness;

/**
 * One client's dot in a script's row: where the script stands on that client.
 */
public enum RunnerState {

    /** Looping normally. */
    RUNNING,

    /** Running, but stuck inside one {@code onLoop} call past the watchdog's threshold. */
    STALLED,

    /** The current run ended by throwing. */
    CRASHED,

    /**
     * The runtime gave up on a thread that would not stop and cut it off from
     * the game. It cannot be started again until that thread exits.
     */
    CUT_OFF,

    /** Ran here before and is stopped now. */
    STOPPED,

    /** The client is not connected: reconnecting, or its pipe is gone. */
    OFFLINE;

    /**
     * Derives the state from what the runner reports. A client that is not
     * connected wins over everything else, because what its runner last said
     * is no longer news.
     */
    public static RunnerState of(RunnerFacts facts) {
        if (!facts.isClientAlive()) {
            return OFFLINE;
        }
        if (facts.liveness().isTerminal()) {
            return CUT_OFF;
        }
        if (facts.isRunning()) {
            return facts.liveness() == Liveness.STALLED ? STALLED : RUNNING;
        }
        return facts.currentCrash().isPresent() ? CRASHED : STOPPED;
    }

    /** Running or stalled: the script is on, whether or not it is making progress. */
    public boolean isActive() {
        return this == RUNNING || this == STALLED;
    }

    /** Stalled, crashed or cut off: something a person should look at. */
    public boolean isProblem() {
        return this == STALLED || this == CRASHED || this == CUT_OFF;
    }
}
