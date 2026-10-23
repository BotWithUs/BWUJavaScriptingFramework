package com.botwithus.bot.core.launcher;

/** The user's answer to a close request, sent as {@code host.ack_close}'s {@code decision}. */
public enum CloseDecision {
    /** The host is closing now. */
    CLOSING("closing"),
    /** The user will not close the host for this update. */
    DECLINED("declined"),
    /** The user will close it later. */
    LATER("later");

    private final String wire;

    CloseDecision(String wire) {
        this.wire = wire;
    }

    /** @return the wire spelling */
    public String wire() {
        return wire;
    }
}
