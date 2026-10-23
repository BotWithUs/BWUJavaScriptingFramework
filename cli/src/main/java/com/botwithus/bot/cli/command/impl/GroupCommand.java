package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.ClientManager;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.GroupMembers;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Groups of clients. A member is a client's account, so it stays in the group
 * while its game is closed; a client is named by its connection's pipe, or by
 * its account UUID when it is not connected.
 */
public class GroupCommand implements Command {

    @Override public String name() { return "group"; }
    @Override public List<String> aliases() { return List.of("g"); }
    @Override public String description() { return "Manage client groups"; }
    @Override public String usage() { return "group <create|delete|rename|add|remove|list|info> [args]"; }

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
            case "rename" -> renameGroup(parsed.arg(1), parsed.arg(2), ctx);
            case "add" -> addToGroup(parsed.arg(1), parsed.arg(2), ctx);
            case "remove" -> removeFromGroup(parsed.arg(1), parsed.arg(2), ctx);
            case "list" -> listGroups(ctx);
            case "info" -> groupInfo(parsed.arg(1), ctx);
            default -> ctx.out().println("Unknown subcommand: " + sub
                    + ". Use: create, delete, rename, add, remove, list, info");
        }
    }

    private void createGroup(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group create <name>");
            return;
        }
        if (ctx.getGroupStore().create(name, Optional.empty()).isEmpty()) {
            ctx.out().println("Group '" + name + "' already exists.");
            return;
        }
        ctx.out().println("Group '" + name + "' created.");
    }

    private void deleteGroup(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group delete <name>");
            return;
        }
        if (manager(ctx).deleteGroup(name)) {
            ctx.out().println("Group '" + name + "' deleted.");
        } else {
            ctx.out().println("Group not found: " + name);
        }
    }

    private void renameGroup(String name, String newName, CliContext ctx) {
        if (name == null || newName == null) {
            ctx.out().println("Usage: group rename <name> <new name>");
            return;
        }
        Optional<ClientGroup> group = ctx.findGroup(name);
        if (group.isEmpty()) {
            ctx.out().println("Group not found: " + name);
        } else if (ctx.getGroupStore().rename(group.get().id(), newName)) {
            ctx.out().println("Group '" + name + "' renamed to '" + newName + "'.");
        } else {
            ctx.out().println("Group '" + newName + "' already exists.");
        }
    }

    private void addToGroup(String groupName, String client, CliContext ctx) {
        if (groupName == null || client == null) {
            ctx.out().println("Usage: group add <group> <connection|account uuid>");
            return;
        }
        String message = switch (manager(ctx).addMember(groupName, client)) {
            case MemberChange.Added _ -> "Added '" + client + "' to group '" + groupName + "'.";
            case MemberChange.AlreadyMember _ -> "'" + client + "' is already in group '" + groupName + "'.";
            case MemberChange.NoSuchGroup _ -> "Group not found: " + groupName;
            case MemberChange.Refused refused -> "Cannot add '" + client + "': " + refused.reason();
        };
        ctx.out().println(message);
    }

    private void removeFromGroup(String groupName, String client, CliContext ctx) {
        if (groupName == null || client == null) {
            ctx.out().println("Usage: group remove <group> <connection|account uuid|unresolved pipe>");
            return;
        }
        if (ctx.findGroup(groupName).isEmpty()) {
            ctx.out().println("Group not found: " + groupName);
        } else if (manager(ctx).removeFromGroup(groupName, client)) {
            ctx.out().println("Removed '" + client + "' from group '" + groupName + "'.");
        } else {
            ctx.out().println("'" + client + "' is not in group '" + groupName + "'.");
        }
    }

    private void listGroups(CliContext ctx) {
        List<ClientGroup> groups = ctx.getGroupStore().all();
        if (groups.isEmpty()) {
            ctx.out().println("No groups defined. Use 'group create <name>' to create one.");
            return;
        }
        for (ClientGroup group : groups) {
            List<String> members = memberLabels(group, ctx);
            String shown = members.isEmpty() ? "(empty)" : String.join(", ", members);
            ctx.out().println("  " + AnsiCodes.colorize(group.name(), AnsiCodes.CYAN) + ": " + shown);
        }
    }

    private void groupInfo(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: group info <name>");
            return;
        }
        Optional<ClientGroup> found = ctx.findGroup(name);
        if (found.isEmpty()) {
            ctx.out().println("Group not found: " + name);
            return;
        }
        ClientGroup group = found.get();
        ctx.out().println("Group: " + AnsiCodes.colorize(group.name(), AnsiCodes.CYAN) + "  (" + group.id() + ")");
        group.description().ifPresent(text -> ctx.out().println("  " + text));
        if (group.members().isEmpty() && group.unresolved().isEmpty()) {
            ctx.out().println("  (no members)");
            return;
        }
        List<Connection> live = ctx.getGroupConnections(group);
        List<String> offline = GroupMembers.offline(group, live);
        for (String uuid : group.members()) {
            String status = offline.contains(uuid)
                    ? AnsiCodes.colorize("disconnected", AnsiCodes.RED)
                    : AnsiCodes.colorize("connected", AnsiCodes.GREEN);
            ctx.out().println("  " + ctx.describeAccount(uuid) + " [" + status + "]");
        }
        for (String pipe : group.unresolved()) {
            ctx.out().println("  " + unresolvedLabel(pipe) + " [" + AnsiCodes.colorize("unknown", AnsiCodes.YELLOW)
                    + "] — 'group remove " + group.name() + " " + pipe + "' to remove it");
        }
    }

    private static List<String> memberLabels(ClientGroup group, CliContext ctx) {
        List<String> labels = new ArrayList<>();
        group.members().forEach(uuid -> labels.add(ctx.describeAccount(uuid)));
        group.unresolved().forEach(pipe -> labels.add(unresolvedLabel(pipe)));
        return labels;
    }

    private static String unresolvedLabel(String pipe) {
        return "unknown client (pipe " + pipe + ")";
    }

    private static ClientManager manager(CliContext ctx) {
        return ctx.getClientManager();
    }
}
