package com.botwithus.bot.cli.command;

import com.botwithus.bot.cli.CliContext;

import java.util.List;

public interface Command {

    String name();

    default List<String> aliases() {
        return List.of();
    }

    String description();

    default String usage() {
        return name();
    }

    void execute(ParsedCommand parsed, CliContext ctx);

    /**
     * Execute and return a structured result for programmatic callers (e.g. GUI panels).
     * Commands that support this should override it. Default delegates to {@link #execute}.
     */
    default CommandResult executeWithResult(ParsedCommand parsed, CliContext ctx) {
        execute(parsed, ctx);
        return CommandResult.ok();
    }
}
