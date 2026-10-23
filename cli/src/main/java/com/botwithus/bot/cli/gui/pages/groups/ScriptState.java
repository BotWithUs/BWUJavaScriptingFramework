package com.botwithus.bot.cli.gui.pages.groups;

/**
 * Where one script is on one client, as a group member's row shows it. Listed
 * in the order a row picks the script it shows first: what needs a look, then
 * what is running, then what is waiting, then what has stopped.
 */
public enum ScriptState {

    /** Its last run ended by throwing. */
    CRASHED,

    /** Stopped, its thread would not drain, and the runtime cut it off from the game. */
    CUT_OFF,

    /** Running, but stuck inside one {@code onLoop()} call past the watchdog's threshold. */
    STALLED,

    RUNNING,

    /** Not started yet: it starts when the client is back. */
    QUEUED,

    /** Ran on this client and was stopped. */
    STOPPED;

    /** Whether the script's thread is running: what a stop would stop. */
    public boolean isActive() {
        return this == RUNNING || this == STALLED;
    }

    /** Whether the script needs a look: it is stuck, crashed or cut off. */
    public boolean needsLook() {
        return this == CRASHED || this == CUT_OFF || this == STALLED;
    }
}
