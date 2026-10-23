package com.botwithus.bot.cli.gui.pages.connections;

/**
 * The three parts of the Connections table, in the order they are listed: a
 * pipe's life runs from found, to connected, to closed, and the table puts the
 * clients that are doing something first.
 */
public enum RowGroup {

    /** Connected, identifying, resuming or reconnecting: the host holds a pipe for it. */
    CONNECTED("Connected"),
    /** A scan saw the pipe; the host is not connected to it. */
    FOUND("Found, not connected"),
    /** The client is gone; a real account is kept until it comes back or is forgotten. */
    CLOSED("Closed · waiting for the account to return");

    private final String title;

    RowGroup(String title) {
        this.title = title;
    }

    /** The heading drawn above the group. */
    public String title() {
        return title;
    }
}
