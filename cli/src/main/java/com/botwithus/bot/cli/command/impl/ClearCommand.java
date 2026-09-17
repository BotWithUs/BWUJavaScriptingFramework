package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.util.List;

public class ClearCommand implements Command {

    private static final int FALLBACK_BLANK_LINES = 50;

    @Override public String name() { return "clear"; }
    @Override public List<String> aliases() { return List.of("cls"); }
    @Override public String description() { return "Clear the screen"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        if (AnsiCodes.isSupported()) {
            ctx.out().print(AnsiCodes.CLEAR_SCREEN);
            ctx.out().flush();
        } else {
            // Fallback: print blank lines
            for (int i = 0; i < FALLBACK_BLANK_LINES; i++) {
                ctx.out().println();
            }
        }
    }
}
