package com.botwithus.bot.cli.gui.pages.dashboard;

/**
 * What a script runner is doing, as the Dashboard sorts and colours it. Declared
 * worst first: the runners table lists problems at the top.
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

    /** Whether the runner belongs in "Needs attention" and the Problems filter. */
    public boolean isProblem() {
        return this == CRASHED || this == STALLED || this == CUT_OFF;
    }
}
