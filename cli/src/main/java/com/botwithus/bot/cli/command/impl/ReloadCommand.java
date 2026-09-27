package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.cli.scripts.ReloadSummary;

import java.util.List;

/**
 * {@code reload}: reloads {@code scripts/} on the active connection or a group.
 *
 * <p>Afterwards it starts nothing, or — with the {@code scripts.restartAfterReload}
 * setting on — exactly the scripts each client was running before. {@code --start}
 * starts every reloaded script instead. {@code --watch} toggles the folder watch,
 * which is the {@code autoReload} setting: it persists, and the Settings page shows it.</p>
 */
public class ReloadCommand implements Command {

    @Override public String name() { return "reload"; }
    @Override public List<String> aliases() { return List.of("rl"); }
    @Override public String description() { return "Hot-reload scripts on active connection"; }
    @Override public String usage() { return "reload [--start] [--group=<name>] [--watch]"; }

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

        AfterReload after = parsed.hasFlag("start") ? AfterReload.START_ALL : ctx.afterReloadSetting();
        String groupName = parsed.flag("group");

        if (groupName != null) {
            reloadGroup(groupName, after, ctx);
            return;
        }

        Connection active = ctx.getActiveConnection();
        if (active == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }
        reload(List.of(active), after, false, ctx);
    }

    private void reloadGroup(String groupName, AfterReload after, CliContext ctx) {
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
        reload(conns, after, true, ctx);
        // Warn about disconnected members
        for (String connName : group.getConnectionNames()) {
            if (conns.stream().noneMatch(c -> c.getName().equals(connName))) {
                ctx.out().println("[" + connName + "] " + AnsiCodes.colorize("Warning: disconnected, skipped.", AnsiCodes.YELLOW));
            }
        }
    }

    private void reload(List<Connection> conns, AfterReload after, boolean isLabelled, CliContext ctx) {
        for (Connection conn : conns) {
            ctx.out().println(prefix(conn.getName(), isLabelled) + "Reloading scripts from scripts/ directory...");
        }
        ReloadSummary summary = ctx.reloadScripts(conns, after);
        for (ReloadSummary.ClientReload client : summary.clients()) {
            String prefix = prefix(client.connection(), isLabelled);
            ctx.out().println(prefix + "Discovered " + client.loaded() + " script(s).");
            if (after == AfterReload.START_ALL && client.loaded() > 0) {
                ctx.out().println(prefix + "Started " + client.loaded() + " script(s).");
            }
        }
        for (var pair : summary.restarted()) {
            ctx.out().println(prefix(pair.connection(), isLabelled) + "Restarted " + pair.script() + ".");
        }
        for (String line : summary.missingLines()) {
            ctx.out().println(AnsiCodes.colorize(line, AnsiCodes.YELLOW));
        }
        boolean hasLoaded = summary.clients().stream().anyMatch(c -> c.loaded() > 0);
        if (!isLabelled && hasLoaded && after == AfterReload.REGISTER_ONLY) {
            ctx.out().println("Scripts loaded but not started. Use 'scripts start <name>' or 'reload --start'.");
        }
    }

    private static String prefix(String connection, boolean isLabelled) {
        return isLabelled ? "[" + connection + "] " : "";
    }
}
