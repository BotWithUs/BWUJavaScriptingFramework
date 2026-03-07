package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.List;

public class ReloadCommand implements Command {

    @Override public String name() { return "reload"; }
    @Override public List<String> aliases() { return List.of("rl"); }
    @Override public String description() { return "Hot-reload scripts on active connection"; }
    @Override public String usage() { return "reload [--start] [--group=<name>]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        if (parsed.hasFlag("watch")) {
            if (ctx.isWatcherRunning()) {
                ctx.stopScriptWatcher();
            } else {
                ctx.startScriptWatcher();
            }
            return;
        }

        boolean autoStart = parsed.hasFlag("start");
        String groupName = parsed.flag("group");

        if (groupName != null) {
            reloadGroup(groupName, autoStart, ctx);
            return;
        }

        ScriptRuntime runtime = ctx.getRuntime();
        if (runtime == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        reloadRuntime(runtime, autoStart, null, ctx);
    }

    private void reloadGroup(String groupName, boolean autoStart, CliContext ctx) {
        var group = ctx.getGroup(groupName);
        if (group == null) {
            ctx.out().println("Group not found: " + groupName);
            return;
        }
        List<Connection> conns = ctx.getGroupConnections(groupName);
        if (conns.isEmpty()) {
            ctx.out().println("No active connections in group '" + groupName + "'.");
            return;
        }
        for (Connection conn : conns) {
            reloadRuntime(conn.getRuntime(), autoStart, conn.getName(), ctx);
        }
        // Warn about disconnected members
        for (String connName : group.getConnectionNames()) {
            if (conns.stream().noneMatch(c -> c.getName().equals(connName))) {
                ctx.out().println("[" + connName + "] " + AnsiCodes.colorize("Warning: disconnected, skipped.", AnsiCodes.YELLOW));
            }
        }
    }

    private void reloadRuntime(ScriptRuntime runtime, boolean autoStart, String connLabel, CliContext ctx) {
        String prefix = connLabel != null ? "[" + connLabel + "] " : "";

        runtime.stopAll();

        ctx.out().println(prefix + "Reloading scripts from scripts/ directory...");
        List<BotScript> scripts = ctx.loadScripts();
        ctx.out().println(prefix + "Discovered " + scripts.size() + " script(s).");

        if (autoStart && !scripts.isEmpty()) {
            runtime.startAll(scripts);
            ctx.out().println(prefix + "Started " + scripts.size() + " script(s).");
        } else if (!scripts.isEmpty()) {
            for (BotScript script : scripts) {
                runtime.registerScript(script);
            }
            if (connLabel == null) {
                ctx.out().println("Scripts loaded but not started. Use 'scripts start <name>' or 'reload --start'.");
            }
        }
    }
}
