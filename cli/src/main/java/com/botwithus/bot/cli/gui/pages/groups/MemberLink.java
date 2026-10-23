package com.botwithus.bot.cli.gui.pages.groups;

/** How the host is linked to a group member's client right now. */
public enum MemberLink {

    /** The client's pipe is open and it is identified. */
    CONNECTED,

    /** The pipe dropped and the host is retrying it. */
    RECONNECTING,

    /** The pipe dropped and nothing is retrying it any more, though the game may still run. */
    NOT_RESPONDING,

    /** The client is gone, or the host has not seen it since it started. */
    CLOSED;

    /** Whether scripts can be started and stopped on the client now. */
    public boolean isConnected() {
        return this == CONNECTED;
    }
}
