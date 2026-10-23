package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;

/**
 * One entry of the console's target picker. A command runs against a live
 * connection, so a target is a pipe, not a client key.
 *
 * @param pipe  the connection's pipe name
 * @param label the client as the user knows it
 */
public record ConsoleTarget(String pipe, String label) {

    public ConsoleTarget {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(label, "label");
    }
}
