package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.OutputLine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Console tab: its output, where a typed command runs, and what can be typed.
 *
 * @param lines     the output buffer, oldest first
 * @param target    the console target's pipe, empty with nothing connected
 * @param targets   every client a command can target, for the target picker
 * @param isMounted whether the output filter is on (the console shows the target only)
 * @param commands  every command name and alias, for Tab completion
 */
public record ConsoleView(List<OutputLine> lines, Optional<String> target, List<ScopeOption> targets,
                          boolean isMounted, List<String> commands) {

    public ConsoleView {
        Objects.requireNonNull(target, "target");
        lines = List.copyOf(lines);
        targets = List.copyOf(targets);
        commands = List.copyOf(commands);
    }
}
