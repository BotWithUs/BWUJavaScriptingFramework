package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.log.LogEntry;

import java.util.List;

/**
 * The Logs tab for one scope and level: the lines, oldest first, and how many
 * errors the scope holds whatever level is picked, for the tab's badge.
 */
public record LogsView(List<LogEntry> lines, int errors) {

    public LogsView {
        lines = List.copyOf(lines);
    }
}
