package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import java.util.List;

public class ExitCommand implements Command {

    @Override public String name() { return "exit"; }
    @Override public List<String> aliases() { return List.of("quit", "q"); }
    @Override public String description() { return "Exit BotWithUs"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        ctx.out().println("Shutting down...");
        ctx.disconnectAll();
        System.exit(0);
    }
}
