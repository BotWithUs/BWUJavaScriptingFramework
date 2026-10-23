package com.botwithus.bot.cli.gui.pages.groups;

/**
 * A group member summed up in one word: the dot the group list draws for it,
 * and what the group's summary counts.
 */
public enum MemberHealth {

    /** Connected and running a script. */
    RUNNING,

    /** Connected, and a script is stuck in one loop. */
    STALLED,

    /** Connected, and a script crashed or was cut off. */
    CRASHED,

    /** Connected with nothing running. */
    IDLE,

    /** The pipe dropped; the client might come back. */
    RECONNECTING,

    /** The client is closed, or not known yet. */
    CLOSED;

    /** Whether the member needs a look: something on it is stuck, crashed or cut off. */
    public boolean needsLook() {
        return this == STALLED || this == CRASHED;
    }
}
