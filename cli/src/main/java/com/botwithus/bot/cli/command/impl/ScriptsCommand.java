package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.List;

public class ScriptsCommand implements Command {

    @Override public String name() { return "scripts"; }
    @Override public List<String> aliases() { return List.of("s"); }
    @Override public String description() { return "Manage scripts on active connection"; }
    @Override public String usage() { return "scripts [list|start <name>|stop <name>|restart <name>|info <name>|config <name>|status] [--group=<name>]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);

        // 'status' works without an active connection — shows all connections
        if ("status".equals(sub)) {
            statusAll(ctx);
            return;
        }

        String groupName = parsed.flag("group");

        // Group-aware start/stop/restart
        if (groupName != null && ("start".equals(sub) || "stop".equals(sub) || "restart".equals(sub))) {
            String scriptName = parsed.arg(1);
            if (scriptName == null) {
                ctx.out().println("Usage: scripts " + sub + " <name> --group=<group>");
                return;
            }
            List<Connection> conns = ctx.getGroupConnections(groupName);
            if (conns.isEmpty()) {
                ctx.out().println("No active connections in group '" + groupName + "'.");
                // Warn about disconnected members
                var group = ctx.getGroup(groupName);
                if (group == null) {
                    ctx.out().println("Group not found: " + groupName);
                } else if (!group.getConnectionNames().isEmpty()) {
                    ctx.out().println("All connections in the group are disconnected.");
                }
                return;
            }
            for (Connection conn : conns) {
                ScriptRuntime runtime = conn.getRuntime();
                switch (sub) {
                    case "start" -> groupStart(scriptName, conn.getName(), runtime, ctx);
                    case "stop" -> groupStop(scriptName, conn.getName(), runtime, ctx);
                    case "restart" -> groupRestart(scriptName, conn.getName(), runtime, ctx);
                }
            }
            warnDisconnected(groupName, conns, ctx);
            return;
        }

        ScriptRuntime runtime = ctx.getRuntime();
        if (runtime == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        if (sub == null || sub.equals("list")) {
            listScripts(runtime, ctx);
        } else if (sub.equals("start")) {
            startScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("stop")) {
            stopScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("restart")) {
            restartScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("info")) {
            infoScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("config")) {
            configScript(parsed.arg(1), runtime, ctx);
        } else {
            ctx.out().println("Unknown subcommand: " + sub + ". Use: list, start, stop, restart, info, config, status");
        }
    }

    private void listScripts(ScriptRuntime runtime, CliContext ctx) {
        List<ScriptRunner> runners = runtime.getRunners();
        if (runners.isEmpty()) {
            ctx.out().println("No scripts loaded. Use 'reload' to discover scripts.");
            return;
        }

        TableFormatter table = new TableFormatter().headers("#", "Name", "Version", "Status");
        int i = 1;
        for (ScriptRunner runner : runners) {
            ScriptManifest m = runner.getManifest();
            String version = m != null ? m.version() : "?";
            String status = runner.isRunning()
                    ? AnsiCodes.colorize("RUNNING", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("STOPPED", AnsiCodes.RED);
            table.row(String.valueOf(i++), runner.getScriptName(), version, status);
        }
        ctx.out().print(table.build());
    }

    private void startScript(String name, ScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: scripts start <name>");
            return;
        }
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            ctx.out().println("Script already running: " + name);
            return;
        }
        runner.start();
        ctx.out().println("Started: " + runner.getScriptName());
    }

    private void stopScript(String name, ScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: scripts stop <name>");
            return;
        }
        if (runtime.stopScript(name)) {
            ctx.out().println("Stopped: " + name);
        } else {
            ctx.out().println("Script not found or not running: " + name);
        }
    }

    private void restartScript(String name, ScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: scripts restart <name>");
            return;
        }
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        ctx.out().println("Restarted: " + runner.getScriptName());
    }

    private void infoScript(String name, ScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: scripts info <name>");
            return;
        }
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Script not found: " + name);
            return;
        }
        ScriptManifest m = runner.getManifest();
        ctx.out().println("  Name:        " + runner.getScriptName());
        ctx.out().println("  Version:     " + (m != null ? m.version() : "?"));
        ctx.out().println("  Author:      " + (m != null && !m.author().isEmpty() ? m.author() : "unknown"));
        ctx.out().println("  Description: " + (m != null && !m.description().isEmpty() ? m.description() : "none"));
        ctx.out().println("  Status:      " + (runner.isRunning() ? "RUNNING" : "STOPPED"));
        ctx.out().println("  Class:       " + runner.getScript().getClass().getName());
        ctx.out().println("  Connection:  " + ctx.getActiveConnectionName());
    }

    private void configScript(String name, ScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: scripts config <name>");
            return;
        }
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Script not found: " + name);
            return;
        }
        var fields = runner.getConfigFields();
        if (fields == null || fields.isEmpty()) {
            ctx.out().println("Script '" + runner.getScriptName() + "' has no configurable fields.");
            return;
        }
        ctx.openConfigPanel(runner);
        ctx.out().println("Opened config panel for: " + runner.getScriptName());
    }

    private void groupStart(String name, String connName, ScriptRuntime runtime, CliContext ctx) {
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("[" + connName + "] Script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            ctx.out().println("[" + connName + "] Already running: " + name);
            return;
        }
        runner.start();
        ctx.out().println("[" + connName + "] Started: " + runner.getScriptName());
    }

    private void groupStop(String name, String connName, ScriptRuntime runtime, CliContext ctx) {
        if (runtime.stopScript(name)) {
            ctx.out().println("[" + connName + "] Stopped: " + name);
        } else {
            ctx.out().println("[" + connName + "] Script not found or not running: " + name);
        }
    }

    private void groupRestart(String name, String connName, ScriptRuntime runtime, CliContext ctx) {
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("[" + connName + "] Script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        ctx.out().println("[" + connName + "] Restarted: " + runner.getScriptName());
    }

    private void warnDisconnected(String groupName, List<Connection> activeConns, CliContext ctx) {
        var group = ctx.getGroup(groupName);
        if (group == null) return;
        for (String connName : group.getConnectionNames()) {
            if (activeConns.stream().noneMatch(c -> c.getName().equals(connName))) {
                ctx.out().println("[" + connName + "] " + AnsiCodes.colorize("Warning: disconnected, skipped.", AnsiCodes.YELLOW));
            }
        }
    }

    private void statusAll(CliContext ctx) {
        if (!ctx.hasConnections()) {
            ctx.out().println("No connections.");
            return;
        }
        String activeName = ctx.getActiveConnectionName();
        TableFormatter table = new TableFormatter().headers("Connection", "Script", "Version", "Status");
        boolean hasScripts = false;
        for (Connection conn : ctx.getConnections()) {
            String connLabel = conn.getName();
            if (connLabel.equals(activeName)) {
                connLabel += " " + AnsiCodes.colorize("*", AnsiCodes.GREEN);
            }
            List<ScriptRunner> runners = conn.getRuntime().getRunners();
            if (runners.isEmpty()) {
                table.row(connLabel, "(none)", "", "");
            } else {
                for (ScriptRunner runner : runners) {
                    hasScripts = true;
                    ScriptManifest m = runner.getManifest();
                    String version = m != null ? m.version() : "?";
                    String status = runner.isRunning()
                            ? AnsiCodes.colorize("RUNNING", AnsiCodes.GREEN)
                            : AnsiCodes.colorize("STOPPED", AnsiCodes.RED);
                    table.row(connLabel, runner.getScriptName(), version, status);
                    connLabel = "";  // only show connection name on first row
                }
            }
        }
        ctx.out().print(table.build());
        if (!hasScripts) {
            ctx.out().println("No scripts loaded on any connection.");
        }
    }
}
