package com.botwithus.bot.cli.gui.pages.installed;

import java.util.Objects;

/**
 * One client's runner of one script, as the row's dot and the detail pane's
 * Clients tab show it.
 *
 * @param clientId    the connection the runner belongs to
 * @param clientName  the account playing there, or the connection name while it is unknown
 * @param state       where the script stands on that client
 * @param detail      the line under the client's name, such as {@code "Running · 41:07 · 142 ms"}
 * @param hasSettings whether the script has settings fields or its own UI to open
 */
public record ClientRun(String clientId, String clientName, RunnerState state, String detail,
                        boolean hasSettings) {

    public ClientRun {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(detail, "detail");
    }
}
