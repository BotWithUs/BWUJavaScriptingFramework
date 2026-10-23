package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.log.LogEntry;

import java.util.List;
import java.util.Map;

/**
 * The Logs tab for one scope and level: the lines, oldest first, how many
 * errors the scope holds whatever level is picked, for the tab's badge, and
 * the name of the client behind each pipe the lines came from.
 *
 * @param clients the client each line's pipe belongs to, as the user knows it;
 *                a pipe missing here is shown as itself
 */
public record LogsView(List<LogEntry> lines, int errors, Map<String, String> clients) {

    public LogsView {
        lines = List.copyOf(lines);
        clients = Map.copyOf(clients);
    }

    /** The lines and errors, with every pipe shown as itself. */
    public LogsView(List<LogEntry> lines, int errors) {
        this(lines, errors, Map.of());
    }

    /** The client a line from {@code pipe} came from, as the user knows it, else the pipe. */
    public String clientOf(String pipe) {
        return clients.getOrDefault(pipe, pipe);
    }
}
