package com.botwithus.bot.cli.gui.runners;

/**
 * What a script runner is doing, as every page that lists runners sees it.
 * {@link RunnerReading#status()} is the one rule that decides it. Declared
 * worst first, so a list sorted by it shows problems at the top.
 */
public enum RunnerStatus {

    /** The current run threw and ended. */
    CRASHED,
    /** Inside one {@code onLoop()} for longer than the watchdog allows. */
    STALLED,
    /** Ignored a stop and was cut off from the game; cannot start again until the host restarts. */
    CUT_OFF,
    /** Looping. */
    RUNNING,
    /** Not running, and not because it crashed. */
    STOPPED;

    /** Stalled, crashed or cut off: something a person should look at. */
    public boolean isProblem() {
        return this == CRASHED || this == STALLED || this == CUT_OFF;
    }

    /** Running or stalled: the script's thread is on, whether or not it is making progress. */
    public boolean isActive() {
        return this == RUNNING || this == STALLED;
    }
}
