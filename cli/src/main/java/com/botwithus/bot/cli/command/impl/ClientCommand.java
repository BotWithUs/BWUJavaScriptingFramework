package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.script.ClientOrchestrator.OpResult;
import com.botwithus.bot.api.script.ClientOrchestrator.ScriptStatusEntry;
import com.botwithus.bot.cli.ClientManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.ConnectionGroup;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;

import java.util.List;
import java.util.Map;

/**
 * CLI command for the ClientManager.
 *
 * <pre>
 * client list                                  — list connected clients
 * client status                                — script status across all clients
 * client status --group=<name>                 — script status for a group
 * client group create <name> [description]     — create a group with optional description
 * client group delete <name>                   — delete a group
 * client group add <group> <client>            — add client to group
 * client group remove <group> <client>         — remove client from group
 * client group list                            — list all groups
 * client group info <name>                     — detailed group info
 * client group describe <name> <description>   — set group description
 * client start <script> --on=<client>          — start script on specific client
 * client start <script> --group=<group>        — start script on group
 * client start <script> --all                  — start script on all clients
 * client stop <script> --on=<client>           — stop script on specific client
 * client stop <script> --group=<group>         — stop script on group
 * client stop <script> --all                   — stop script on all clients
 * client restart <script> --on=<client>        — restart script on specific client
 * client restart <script> --group=<group>      — restart script on group
 * client restart <script> --all                — restart script on all clients
 * client stopall --group=<group>               — stop all scripts on group
 * client stopall --all                         — stop all scripts on all clients
 * </pre>
 */
public class ClientCommand implements Command {

    @Override public String name() { return "client"; }
    @Override public List<String> aliases() { return List.of("cm", "clients"); }
    @Override public String description() { return "Manage clients, groups, and cross-client scripts"; }
    @Override public String usage() { return "client <list|status|group|start|stop|restart|stopall> [args] [--group=<g>|--on=<c>|--all]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        ClientManager mgr = ctx.getClientManager();
        if (mgr == null) {
            ctx.out().println("ClientManager not initialised.");
            return;
        }

        String sub = parsed.arg(0);
        if (sub == null) {
            ctx.out().println("Usage: " + usage());
            return;
        }

        switch (sub) {
            case "list" -> listClients(mgr, ctx);
            case "status" -> status(parsed, mgr, ctx);
            case "group", "g" -> groupSub(parsed, mgr, ctx);
            case "start" -> scriptAction(parsed, mgr, ctx, "start");
            case "stop" -> scriptAction(parsed, mgr, ctx, "stop");
            case "restart" -> scriptAction(parsed, mgr, ctx, "restart");
            case "stopall" -> stopAll(parsed, mgr, ctx);
            default -> ctx.out().println("Unknown subcommand: " + sub + ". Use: list, status, group, start, stop, restart, stopall");
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    private void listClients(ClientManager mgr, CliContext ctx) {
        var clients = mgr.getClients();
        if (clients.isEmpty()) {
            ctx.out().println("No connected clients. Use 'connect' to add one.");
            return;
        }
        TableFormatter table = new TableFormatter().headers("Client", "Status", "Scripts");
        for (Connection conn : clients) {
            String status = conn.isAlive()
                    ? AnsiCodes.colorize("connected", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("disconnected", AnsiCodes.RED);
            long running = conn.getRuntime().getRunners().stream()
                    .filter(r -> r.isRunning()).count();
            long total = conn.getRuntime().getRunners().size();
            String scripts = running + "/" + total + " running";
            table.row(conn.getName(), status, scripts);
        }
        ctx.out().print(table.build());
    }

    // ── status ───────────────────────────────────────────────────────────────

    private void status(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String groupName = parsed.flag("group");
        List<ScriptStatusEntry> statuses = groupName != null
                ? mgr.getStatusForGroup(groupName)
                : mgr.getStatusAll();

        if (statuses.isEmpty()) {
            ctx.out().println(groupName != null
                    ? "No scripts on clients in group '" + groupName + "'."
                    : "No scripts loaded on any client.");
            return;
        }

        String header = groupName != null
                ? "Script status for group '" + groupName + "':"
                : "Script status across all clients:";
        ctx.out().println(header);

        TableFormatter table = new TableFormatter().headers("Client", "Script", "Version", "Status");
        for (ScriptStatusEntry s : statuses) {
            String status = s.running()
                    ? AnsiCodes.colorize("RUNNING", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("STOPPED", AnsiCodes.RED);
            table.row(s.clientName(), s.scriptName(), s.version(), status);
        }
        ctx.out().print(table.build());
    }

    // ── group subcommands ────────────────────────────────────────────────────

    private void groupSub(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String sub = parsed.arg(1);
        if (sub == null) {
            ctx.out().println("Usage: client group <create|delete|add|remove|list|info|describe> [args]");
            return;
        }

        switch (sub) {
            case "create" -> groupCreate(parsed, mgr, ctx);
            case "delete" -> groupDelete(parsed, mgr, ctx);
            case "add" -> groupAdd(parsed, mgr, ctx);
            case "remove" -> groupRemove(parsed, mgr, ctx);
            case "list" -> groupList(mgr, ctx);
            case "info" -> groupInfo(parsed, mgr, ctx);
            case "describe" -> groupDescribe(parsed, mgr, ctx);
            default -> ctx.out().println("Unknown group subcommand: " + sub);
        }
    }

    private void groupCreate(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String name = parsed.arg(2);
        if (name == null) {
            ctx.out().println("Usage: client group create <name> [description]");
            return;
        }
        if (mgr.getGroup(name) != null) {
            ctx.out().println("Group '" + name + "' already exists.");
            return;
        }
        // Everything after the group name is the description
        String desc = buildRemainingArgs(parsed, 3);
        mgr.createGroup(name, desc.isEmpty() ? null : desc);
        ctx.out().println("Group '" + name + "' created."
                + (desc.isEmpty() ? "" : " Description: " + desc));
    }

    private void groupDelete(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String name = parsed.arg(2);
        if (name == null) {
            ctx.out().println("Usage: client group delete <name>");
            return;
        }
        if (mgr.deleteGroup(name)) {
            ctx.out().println("Group '" + name + "' deleted.");
        } else {
            ctx.out().println("Group not found: " + name);
        }
    }

    private void groupAdd(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String groupName = parsed.arg(2);
        String clientName = parsed.arg(3);
        if (groupName == null || clientName == null) {
            ctx.out().println("Usage: client group add <group> <client>");
            return;
        }
        if (mgr.addToGroup(groupName, clientName)) {
            ctx.out().println("Added '" + clientName + "' to group '" + groupName + "'.");
        } else {
            ctx.out().println("Group not found: " + groupName);
        }
    }

    private void groupRemove(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String groupName = parsed.arg(2);
        String clientName = parsed.arg(3);
        if (groupName == null || clientName == null) {
            ctx.out().println("Usage: client group remove <group> <client>");
            return;
        }
        if (mgr.removeFromGroup(groupName, clientName)) {
            ctx.out().println("Removed '" + clientName + "' from group '" + groupName + "'.");
        } else {
            ctx.out().println("Group not found: " + groupName);
        }
    }

    private void groupList(ClientManager mgr, CliContext ctx) {
        Map<String, ConnectionGroup> groups = mgr.getGroups();
        if (groups.isEmpty()) {
            ctx.out().println("No groups. Use 'client group create <name> [desc]' to create one.");
            return;
        }
        TableFormatter table = new TableFormatter().headers("Group", "Description", "Members");
        for (ConnectionGroup group : groups.values()) {
            String desc = group.getDescription() != null ? group.getDescription() : "";
            String members = group.getConnectionNames().isEmpty()
                    ? "(empty)"
                    : String.join(", ", group.getConnectionNames());
            table.row(group.getName(), desc, members);
        }
        ctx.out().print(table.build());
    }

    private void groupInfo(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String name = parsed.arg(2);
        if (name == null) {
            ctx.out().println("Usage: client group info <name>");
            return;
        }
        ConnectionGroup group = mgr.getGroup(name);
        if (group == null) {
            ctx.out().println("Group not found: " + name);
            return;
        }

        ctx.out().println("Group: " + AnsiCodes.colorize(group.getName(), AnsiCodes.CYAN));
        if (group.getDescription() != null) {
            ctx.out().println("  Description: " + group.getDescription());
        }
        if (group.getConnectionNames().isEmpty()) {
            ctx.out().println("  (no members)");
            return;
        }
        ctx.out().println("  Members:");
        for (String memberName : group.getConnectionNames()) {
            boolean alive = mgr.isClientAlive(memberName);
            String status = alive
                    ? AnsiCodes.colorize("connected", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("disconnected", AnsiCodes.RED);
            ctx.out().println("    " + memberName + " [" + status + "]");
        }

        // Show scripts running on group members
        List<ScriptStatusEntry> statuses = mgr.getStatusForGroup(name);
        if (!statuses.isEmpty()) {
            ctx.out().println("  Scripts:");
            for (ScriptStatusEntry s : statuses) {
                String st = s.running()
                        ? AnsiCodes.colorize("RUNNING", AnsiCodes.GREEN)
                        : AnsiCodes.colorize("STOPPED", AnsiCodes.RED);
                ctx.out().println("    [" + s.clientName() + "] " + s.scriptName() + " " + st);
            }
        }
    }

    private void groupDescribe(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String name = parsed.arg(2);
        if (name == null) {
            ctx.out().println("Usage: client group describe <name> <description>");
            return;
        }
        ConnectionGroup group = mgr.getGroup(name);
        if (group == null) {
            ctx.out().println("Group not found: " + name);
            return;
        }
        String desc = buildRemainingArgs(parsed, 3);
        if (desc.isEmpty()) {
            ctx.out().println("Usage: client group describe <name> <description>");
            return;
        }
        group.setDescription(desc);
        ctx.out().println("Group '" + name + "' description set to: " + desc);
    }

    // ── script actions (start/stop/restart) ──────────────────────────────────

    private void scriptAction(ParsedCommand parsed, ClientManager mgr, CliContext ctx, String action) {
        String scriptName = parsed.arg(1);
        if (scriptName == null) {
            ctx.out().println("Usage: client " + action + " <script> [--on=<client>|--group=<group>|--all]");
            return;
        }

        String clientName = parsed.flag("on");
        String groupName = parsed.flag("group");
        boolean all = parsed.hasFlag("all");

        if (clientName != null) {
            // Single client
            OpResult r = switch (action) {
                case "start" -> mgr.startScript(clientName, scriptName);
                case "stop" -> mgr.stopScript(clientName, scriptName);
                case "restart" -> mgr.restartScript(clientName, scriptName);
                default -> throw new IllegalStateException();
            };
            printResult(r, ctx);
        } else if (groupName != null) {
            // Group
            List<OpResult> results = switch (action) {
                case "start" -> mgr.startScriptOnGroup(groupName, scriptName);
                case "stop" -> mgr.stopScriptOnGroup(groupName, scriptName);
                case "restart" -> mgr.restartScriptOnGroup(groupName, scriptName);
                default -> throw new IllegalStateException();
            };
            printResults(results, ctx);
        } else if (all) {
            // All clients
            List<OpResult> results = switch (action) {
                case "start" -> mgr.startScriptOnAll(scriptName);
                case "stop" -> mgr.stopScriptOnAll(scriptName);
                case "restart" -> mgr.restartScriptOnAll(scriptName);
                default -> throw new IllegalStateException();
            };
            printResults(results, ctx);
        } else {
            ctx.out().println("Specify target: --on=<client>, --group=<group>, or --all");
        }
    }

    // ── stopall ──────────────────────────────────────────────────────────────

    private void stopAll(ParsedCommand parsed, ClientManager mgr, CliContext ctx) {
        String groupName = parsed.flag("group");
        boolean all = parsed.hasFlag("all");

        if (groupName != null) {
            List<OpResult> results = mgr.stopAllScriptsOnGroup(groupName);
            printResults(results, ctx);
        } else if (all) {
            mgr.stopAllScriptsOnAll();
            ctx.out().println("Stopped all scripts on all clients.");
        } else {
            ctx.out().println("Specify target: --group=<group> or --all");
        }
    }

    // ── output helpers ───────────────────────────────────────────────────────

    private void printResult(OpResult r, CliContext ctx) {
        if (r.success()) {
            ctx.out().println("[" + r.clientName() + "] " + r.scriptName() + ": " + r.message());
        } else {
            ctx.out().println("[" + r.clientName() + "] "
                    + AnsiCodes.colorize(r.message(), AnsiCodes.RED)
                    + (r.scriptName() != null ? " (" + r.scriptName() + ")" : ""));
        }
    }

    private void printResults(List<OpResult> results, CliContext ctx) {
        for (OpResult r : results) {
            printResult(r, ctx);
        }
    }

    private String buildRemainingArgs(ParsedCommand parsed, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; ; i++) {
            String arg = parsed.arg(i);
            if (arg == null) break;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(arg);
        }
        return sb.toString();
    }
}
