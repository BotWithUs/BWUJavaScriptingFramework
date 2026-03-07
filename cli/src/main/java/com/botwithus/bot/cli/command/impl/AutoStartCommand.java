package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AutoStartCommand implements Command {

    private final ScriptProfileStore profileStore;
    private final AutoStartManager autoStartManager;

    public AutoStartCommand(ScriptProfileStore profileStore, AutoStartManager autoStartManager) {
        this.profileStore = profileStore;
        this.autoStartManager = autoStartManager;
    }

    @Override public String name() { return "autostart"; }
    @Override public List<String> aliases() { return List.of("as"); }
    @Override public String description() { return "Manage script auto-start profiles per account"; }
    @Override public String usage() {
        return "autostart [list|add <script>|remove <script>|enable|disable|save|clear [account]|group <name> add|remove <script>|settings]";
    }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);
        if (sub == null || sub.equals("list")) {
            listProfiles(ctx);
        } else {
            switch (sub.toLowerCase()) {
                case "add" -> addScript(parsed.arg(1), ctx);
                case "remove", "rm" -> removeScript(parsed.arg(1), ctx);
                case "enable" -> setEnabled(true, ctx);
                case "disable" -> setEnabled(false, ctx);
                case "save" -> saveCurrentState(ctx);
                case "clear" -> clearProfile(parsed.arg(1), ctx);
                case "group" -> handleGroup(parsed, ctx);
                case "settings" -> showSettings(ctx);
                case "on" -> {
                    profileStore.setAutoConnect(true);
                    profileStore.saveSettings();
                    autoStartManager.start();
                    ctx.out().println("Auto-connect enabled. Background scanning started.");
                }
                case "off" -> {
                    profileStore.setAutoConnect(false);
                    profileStore.saveSettings();
                    autoStartManager.stop();
                    ctx.out().println("Auto-connect disabled. Background scanning stopped.");
                }
                default -> ctx.out().println("Unknown subcommand: " + sub + ". " + usage());
            }
        }
    }

    private void listProfiles(CliContext ctx) {
        Map<String, List<String>> accounts = profileStore.listAccountProfiles();
        Map<String, List<String>> groups = profileStore.listGroupProfiles();

        if (accounts.isEmpty() && groups.isEmpty()) {
            ctx.out().println("No auto-start profiles configured.");
            ctx.out().println("Use 'autostart save' to save current running scripts as a profile.");
            return;
        }

        if (!accounts.isEmpty()) {
            ctx.out().println("Account Profiles:");
            TableFormatter table = new TableFormatter().headers("Account", "Auto-Start", "Scripts");
            for (var entry : accounts.entrySet()) {
                String name = entry.getKey();
                boolean enabled = profileStore.isAutoStart(name);
                String status = enabled
                        ? AnsiCodes.colorize("ON", AnsiCodes.GREEN)
                        : AnsiCodes.colorize("OFF", AnsiCodes.RED);
                String scripts = entry.getValue().isEmpty() ? "(none)" : String.join(", ", entry.getValue());
                table.row(name, status, scripts);
            }
            ctx.out().print(table.build());
        }

        if (!groups.isEmpty()) {
            ctx.out().println("Group Profiles:");
            TableFormatter table = new TableFormatter().headers("Group", "Auto-Start", "Scripts");
            for (var entry : groups.entrySet()) {
                String name = entry.getKey();
                boolean enabled = profileStore.isGroupAutoStart(name);
                String status = enabled
                        ? AnsiCodes.colorize("ON", AnsiCodes.GREEN)
                        : AnsiCodes.colorize("OFF", AnsiCodes.RED);
                String scripts = entry.getValue().isEmpty() ? "(none)" : String.join(", ", entry.getValue());
                table.row(name, status, scripts);
            }
            ctx.out().print(table.build());
        }
    }

    private void addScript(String scriptName, CliContext ctx) {
        if (scriptName == null) {
            ctx.out().println("Usage: autostart add <scriptName>");
            return;
        }
        String accountName = getActiveAccountName(ctx);
        if (accountName == null) return;

        List<String> scripts = new ArrayList<>(profileStore.getAccountScripts(accountName));
        if (scripts.stream().anyMatch(s -> s.equalsIgnoreCase(scriptName))) {
            ctx.out().println("Script '" + scriptName + "' already in auto-start for " + accountName + ".");
            return;
        }
        scripts.add(scriptName);
        profileStore.setAccountScripts(accountName, scripts);
        ctx.out().println("Added '" + scriptName + "' to auto-start for " + accountName + ".");
    }

    private void removeScript(String scriptName, CliContext ctx) {
        if (scriptName == null) {
            ctx.out().println("Usage: autostart remove <scriptName>");
            return;
        }
        String accountName = getActiveAccountName(ctx);
        if (accountName == null) return;

        List<String> scripts = new ArrayList<>(profileStore.getAccountScripts(accountName));
        boolean removed = scripts.removeIf(s -> s.equalsIgnoreCase(scriptName));
        if (!removed) {
            ctx.out().println("Script '" + scriptName + "' not found in auto-start for " + accountName + ".");
            return;
        }
        profileStore.setAccountScripts(accountName, scripts);
        ctx.out().println("Removed '" + scriptName + "' from auto-start for " + accountName + ".");
    }

    private void setEnabled(boolean enabled, CliContext ctx) {
        String accountName = getActiveAccountName(ctx);
        if (accountName == null) return;
        profileStore.setAutoStart(accountName, enabled);
        ctx.out().println("Auto-start " + (enabled ? "enabled" : "disabled") + " for " + accountName + ".");
    }

    private void saveCurrentState(CliContext ctx) {
        if (!ctx.hasActiveConnection()) {
            ctx.out().println("No active connection.");
            return;
        }
        Connection conn = ctx.getActiveConnection();
        String accountName = conn.getAccountName();
        if (accountName == null || accountName.isBlank()) {
            // Try to probe account info now
            ctx.out().println("Account name not known. Probing...");
            try {
                Map<String, Object> info = conn.getRpc().callSync("get_account_info", Map.of());
                accountName = getString(info, "display_name");
                if (accountName == null || accountName.isEmpty()) {
                    accountName = getString(info, "jx_display_name");
                }
                if (accountName != null && !accountName.isEmpty()) {
                    conn.setAccountName(accountName);
                    conn.setAccountInfo(info);
                }
            } catch (Exception e) {
                ctx.out().println("Failed to probe account info: " + e.getMessage());
                return;
            }
        }

        if (accountName == null || accountName.isBlank()) {
            ctx.out().println("Cannot determine account name. Connect and log in first.");
            return;
        }

        autoStartManager.saveState(conn);
        List<String> saved = profileStore.getAccountScripts(accountName);
        ctx.out().println("Saved profile for " + accountName + ": " + (saved.isEmpty() ? "(no running scripts)" : String.join(", ", saved)));
    }

    private void clearProfile(String accountName, CliContext ctx) {
        if (accountName == null) {
            accountName = getActiveAccountName(ctx);
            if (accountName == null) return;
        }
        if (profileStore.clearAccountProfile(accountName)) {
            ctx.out().println("Cleared profile for " + accountName + ".");
        } else {
            ctx.out().println("No profile found for " + accountName + ".");
        }
    }

    private void handleGroup(ParsedCommand parsed, CliContext ctx) {
        String groupName = parsed.arg(1);
        String action = parsed.arg(2);
        String scriptName = parsed.arg(3);

        if (groupName == null || action == null) {
            ctx.out().println("Usage: autostart group <groupName> add|remove <script>");
            return;
        }

        switch (action.toLowerCase()) {
            case "add" -> {
                if (scriptName == null) {
                    ctx.out().println("Usage: autostart group " + groupName + " add <script>");
                    return;
                }
                List<String> scripts = new ArrayList<>(profileStore.getGroupScripts(groupName));
                if (scripts.stream().anyMatch(s -> s.equalsIgnoreCase(scriptName))) {
                    ctx.out().println("Script '" + scriptName + "' already in group " + groupName + ".");
                    return;
                }
                scripts.add(scriptName);
                profileStore.setGroupScripts(groupName, scripts);
                ctx.out().println("Added '" + scriptName + "' to group " + groupName + ".");
            }
            case "remove", "rm" -> {
                if (scriptName == null) {
                    ctx.out().println("Usage: autostart group " + groupName + " remove <script>");
                    return;
                }
                List<String> scripts = new ArrayList<>(profileStore.getGroupScripts(groupName));
                boolean removed = scripts.removeIf(s -> s.equalsIgnoreCase(scriptName));
                if (!removed) {
                    ctx.out().println("Script '" + scriptName + "' not in group " + groupName + ".");
                    return;
                }
                profileStore.setGroupScripts(groupName, scripts);
                ctx.out().println("Removed '" + scriptName + "' from group " + groupName + ".");
            }
            case "list" -> {
                List<String> scripts = profileStore.getGroupScripts(groupName);
                if (scripts.isEmpty()) {
                    ctx.out().println("No scripts in group " + groupName + ".");
                } else {
                    ctx.out().println("Group " + groupName + ": " + String.join(", ", scripts));
                }
            }
            default -> ctx.out().println("Unknown group action: " + action + ". Use add, remove, or list.");
        }
    }

    private void showSettings(CliContext ctx) {
        ctx.out().println("Auto-Start Settings:");
        ctx.out().println("  autoConnect: " + profileStore.isAutoConnect());
        ctx.out().println("  pipePrefix:  " + profileStore.getPipePrefix());
        ctx.out().println("  probeLobby:  " + profileStore.isProbeLobby());
        ctx.out().println("  scanInterval: " + profileStore.getScanIntervalMs() + "ms");
    }

    private String getActiveAccountName(CliContext ctx) {
        if (!ctx.hasActiveConnection()) {
            ctx.out().println("No active connection.");
            return null;
        }
        Connection conn = ctx.getActiveConnection();
        String name = conn.getAccountName();
        if (name == null || name.isBlank()) {
            ctx.out().println("Account name not known. Use 'autostart save' to probe and save, or connect to a logged-in client.");
            return null;
        }
        return name;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
