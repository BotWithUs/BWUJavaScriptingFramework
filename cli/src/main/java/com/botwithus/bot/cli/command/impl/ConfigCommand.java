package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.config.CliConfig;

import java.util.List;

public class ConfigCommand implements Command {

    private final CliConfig config;

    public ConfigCommand(CliConfig config) {
        this.config = config;
    }

    @Override public String name() { return "config"; }
    @Override public List<String> aliases() { return List.of("cfg"); }
    @Override public String description() { return "View or modify CLI configuration"; }
    @Override public String usage() { return "config [show | set <key> <value> | save]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);

        if (sub == null || "show".equals(sub)) {
            showConfig(ctx);
            return;
        }

        if ("set".equals(sub)) {
            String key = parsed.arg(1);
            String value = parsed.arg(2);
            if (key == null || value == null) {
                ctx.out().println("Usage: config set <key> <value>");
                return;
            }
            config.set(key, value);
            ctx.out().println("Set " + key + " = " + value);
            return;
        }

        if ("save".equals(sub)) {
            config.save();
            ctx.out().println("Configuration saved.");
            return;
        }

        ctx.out().println("Unknown subcommand: " + sub);
    }

    private void showConfig(CliContext ctx) {
        ctx.out().println("CLI Configuration:");
        var props = config.getProperties();
        if (props.isEmpty()) {
            ctx.out().println("  (no configuration set)");
            return;
        }
        for (var entry : props.entrySet()) {
            ctx.out().println("  " + entry.getKey() + " = " + entry.getValue());
        }
    }
}
