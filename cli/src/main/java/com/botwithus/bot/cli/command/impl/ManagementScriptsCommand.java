package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;

import java.util.List;

/**
 * CLI command for managing management scripts (scripts/management/).
 * Supports list, start, stop, restart, reload, and info subcommands.
 */
public class ManagementScriptsCommand implements Command {

    @Override public String name() { return "mgmt"; }
    @Override public List<String> aliases() { return List.of("management", "m"); }
    @Override public String description() { return "Manage management scripts (cross-client orchestration)"; }
    @Override public String usage() { return "mgmt [list|start <name>|stop <name>|restart <name>|reload|info <name>|stopall]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        if (runtime == null) {
            ctx.out().println("Management runtime not initialized.");
            return;
        }

        String sub = parsed.arg(0);

        if (sub == null || sub.equals("list")) {
            listScripts(runtime, ctx);
        } else if (sub.equals("start")) {
            startScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("stop")) {
            stopScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("restart")) {
            restartScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("reload")) {
            reloadScripts(runtime, ctx);
        } else if (sub.equals("load")) {
            loadScripts(runtime, ctx);
        } else if (sub.equals("info")) {
            infoScript(parsed.arg(1), runtime, ctx);
        } else if (sub.equals("stopall")) {
            runtime.stopAll();
            ctx.out().println("Stopped all management scripts.");
        } else {
            ctx.out().println("Unknown subcommand: " + sub + ". Use: list, start, stop, restart, reload, load, info, stopall");
        }
    }

    private void listScripts(ManagementScriptRuntime runtime, CliContext ctx) {
        List<ManagementScriptRunner> runners = runtime.getRunners();
        if (runners.isEmpty()) {
            ctx.out().println("No management scripts loaded. Use 'mgmt load' or 'mgmt reload' to discover scripts.");
            return;
        }

        TableFormatter table = new TableFormatter().headers("#", "Name", "Author", "Version", "Status");
        int i = 1;
        for (ManagementScriptRunner runner : runners) {
            ScriptManifest m = runner.getManifest();
            String author = m != null && !m.author().isEmpty() ? m.author() : "-";
            String version = m != null ? m.version() : "?";
            String status = runner.isRunning()
                    ? AnsiCodes.colorize("RUNNING", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("STOPPED", AnsiCodes.RED);
            table.row(String.valueOf(i++), runner.getScriptName(), author, version, status);
        }
        ctx.out().print(table.build());
    }

    private void startScript(String name, ManagementScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: mgmt start <name>");
            return;
        }
        ManagementScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Management script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            ctx.out().println("Already running: " + name);
            return;
        }
        runner.start();
        ctx.out().println("Started: " + runner.getScriptName());
    }

    private void stopScript(String name, ManagementScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: mgmt stop <name>");
            return;
        }
        if (runtime.stopScript(name)) {
            ctx.out().println("Stopped: " + name);
        } else {
            ctx.out().println("Management script not found or not running: " + name);
        }
    }

    private void restartScript(String name, ManagementScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: mgmt restart <name>");
            return;
        }
        ManagementScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Management script not found: " + name);
            return;
        }
        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        ctx.out().println("Restarted: " + runner.getScriptName());
    }

    private void reloadScripts(ManagementScriptRuntime runtime, CliContext ctx) {
        runtime.stopAll();
        ctx.out().println("Reloading management scripts from scripts/management/...");
        List<ManagementScript> scripts = ctx.loadManagementScripts();
        if (scripts.isEmpty()) {
            ctx.out().println("No management scripts found.");
            return;
        }
        for (ManagementScript script : scripts) {
            runtime.registerScript(script);
        }
        ctx.out().println("Reloaded " + scripts.size() + " management script(s).");
    }

    private void loadScripts(ManagementScriptRuntime runtime, CliContext ctx) {
        ctx.out().println("Loading management scripts from scripts/management/...");
        List<ManagementScript> scripts = ctx.loadManagementScripts();
        if (scripts.isEmpty()) {
            ctx.out().println("No management scripts found.");
            return;
        }
        for (ManagementScript script : scripts) {
            runtime.registerScript(script);
        }
        ctx.out().println("Loaded " + scripts.size() + " management script(s).");
    }

    private void infoScript(String name, ManagementScriptRuntime runtime, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: mgmt info <name>");
            return;
        }
        ManagementScriptRunner runner = runtime.findRunner(name);
        if (runner == null) {
            ctx.out().println("Management script not found: " + name);
            return;
        }
        ScriptManifest m = runner.getManifest();
        ctx.out().println("  Name:        " + runner.getScriptName());
        ctx.out().println("  Version:     " + (m != null ? m.version() : "?"));
        ctx.out().println("  Author:      " + (m != null && !m.author().isEmpty() ? m.author() : "unknown"));
        ctx.out().println("  Description: " + (m != null && !m.description().isEmpty() ? m.description() : "none"));
        ctx.out().println("  Status:      " + (runner.isRunning() ? "RUNNING" : "STOPPED"));
        ctx.out().println("  Class:       " + runner.getScript().getClass().getName());
        ctx.out().println("  Type:        Management (connection-independent)");

        var configFields = runner.getConfigFields();
        if (configFields != null && !configFields.isEmpty()) {
            ctx.out().println("  Config:      " + configFields.size() + " field(s)");
        }
        if (runner.getScript().getUI() != null) {
            ctx.out().println("  Custom UI:   yes");
        }
    }
}
