package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.ConnectionGroup;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.util.List;
import java.util.Map;

public class GroupCommand implements Command {

    @Override public String name() { return "group"; }
    @Override public List<String> aliases() { return List.of("g"); }
    @Override public String description() { return "Manage connection groups"; }
    @Override public String usage() { return "group <create|delete|add|remove|list|info> [args]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);
        if (sub == null) {
            ctx.out().println("Usage: " + usage());
            return;
        }

        switch (sub) {
            case "create" -> createGroup(parsed.arg(1), ctx);
            case "delete" -> deleteGroup(parsed.arg(1), ctx);
            case "add" -> addToGroup(parsed.arg(1), parsed.arg(2), ctx);
            case "remove" -> removeFromGroup(parsed.arg(1), parsed.arg(2), ctx);
            case "list" -> listGroups(ctx);
            case "info" -> groupInfo(parsed.arg(1), ctx);
            default -> ctx.out().println("Unknown subcommand: " + sub + ". Use: create, delete, add, remove, list, info");
        }
    }

    private void createGroup(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group create <name>");
            return;
        }
        if (ctx.getGroup(name) != null) {
            ctx.out().println("Group '" + name + "' already exists.");
            return;
        }
        ctx.createGroup(name);
        ctx.out().println("Group '" + name + "' created.");
    }

    private void deleteGroup(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group delete <name>");
            return;
        }
        if (ctx.deleteGroup(name)) {
            ctx.out().println("Group '" + name + "' deleted.");
        } else {
            ctx.out().println("Group not found: " + name);
        }
    }

    private void addToGroup(String groupName, String connName, CliContext ctx) {
        if (groupName == null || connName == null) {
            ctx.out().println("Usage: group add <group> <connection>");
            return;
        }
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) {
            ctx.out().println("Group not found: " + groupName);
            return;
        }
        if (group.contains(connName)) {
            ctx.out().println("'" + connName + "' is already in group '" + groupName + "'.");
            return;
        }
        ctx.addToGroup(groupName, connName);
        ctx.out().println("Added '" + connName + "' to group '" + groupName + "'.");
    }

    private void removeFromGroup(String groupName, String connName, CliContext ctx) {
        if (groupName == null || connName == null) {
            ctx.out().println("Usage: group remove <group> <connection>");
            return;
        }
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) {
            ctx.out().println("Group not found: " + groupName);
            return;
        }
        if (!group.contains(connName)) {
            ctx.out().println("'" + connName + "' is not in group '" + groupName + "'.");
            return;
        }
        ctx.removeFromGroup(groupName, connName);
        ctx.out().println("Removed '" + connName + "' from group '" + groupName + "'.");
    }

    private void listGroups(CliContext ctx) {
        Map<String, ConnectionGroup> groups = ctx.getGroups();
        if (groups.isEmpty()) {
            ctx.out().println("No groups defined. Use 'group create <name>' to create one.");
            return;
        }
        for (ConnectionGroup group : groups.values()) {
            String members = group.getConnectionNames().isEmpty()
                    ? "(empty)"
                    : String.join(", ", group.getConnectionNames());
            ctx.out().println("  " + AnsiCodes.colorize(group.getName(), AnsiCodes.CYAN) + ": " + members);
        }
    }

    private void groupInfo(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group info <name>");
            return;
        }
        ConnectionGroup group = ctx.getGroup(name);
        if (group == null) {
            ctx.out().println("Group not found: " + name);
            return;
        }
        ctx.out().println("Group: " + AnsiCodes.colorize(group.getName(), AnsiCodes.CYAN));
        if (group.getConnectionNames().isEmpty()) {
            ctx.out().println("  (no connections)");
            return;
        }
        for (String connName : group.getConnectionNames()) {
            List<Connection> allConns = ctx.getGroupConnections(name);
            boolean active = allConns.stream().anyMatch(c -> c.getName().equals(connName));
            String status = active
                    ? AnsiCodes.colorize("connected", AnsiCodes.GREEN)
                    : AnsiCodes.colorize("disconnected", AnsiCodes.RED);
            ctx.out().println("  " + connName + " [" + status + "]");
        }
    }
}
