package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConnectCommand implements Command {

    @Override public String name() { return "connect"; }
    @Override public List<String> aliases() { return List.of("conn"); }
    @Override public String description() { return "Manage pipe connections"; }
    @Override public String usage() { return "connect [<pipe>|<number>|scan [filter]|disconnect [name|--all]|list|use <name>|status]"; }

    private static final int LOBBY_POLL_INTERVAL_MS = 500;
    private static final int LOBBY_POLL_TIMEOUT_MS = 15_000;

    /** Info gathered from probing a pipe. */
    private record PipeInfo(String pipeName, String displayName, int worldId, boolean loggedIn, boolean isMember) {}

    /** Cached results from the last scan/autoConnect for number-based selection. */
    private List<PipeInfo> lastScanResults;

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);
        if (sub == null) {
            autoConnect(ctx);
        } else {
            switch (sub.toLowerCase()) {
                case "scan" -> scan(parsed.arg(1), ctx);
                case "disconnect", "dc" -> {
                    boolean force = parsed.hasFlag("force");
                    if (parsed.hasFlag("all")) {
                        ctx.disconnectAll(force);
                    } else {
                        ctx.disconnect(parsed.arg(1), force);
                    }
                }
                case "reconnect", "rc" -> {
                    String name = ctx.getActiveConnectionName();
                    ctx.disconnect(null);
                    ctx.connect(name);
                }
                case "list", "ls" -> listConnections(ctx);
                case "use" -> useConnection(parsed.arg(1), ctx);
                case "status" -> status(ctx);
                default -> {
                    // Check if it's a number referencing a previous scan result
                    if (lastScanResults != null && isNumber(sub)) {
                        int index = Integer.parseInt(sub) - 1;
                        if (index >= 0 && index < lastScanResults.size()) {
                            ctx.connect(lastScanResults.get(index).pipeName());
                        } else {
                            ctx.out().println("Invalid selection. Choose 1-" + lastScanResults.size() + ".");
                        }
                    } else {
                        ctx.connect(sub);
                    }
                }
            }
        }
    }

    private void autoConnect(CliContext ctx) {
        List<String> pipes = PipeClient.scanPipes("BotWithUs");
        if (pipes.isEmpty()) {
            ctx.out().println("No BotWithUs pipes found. Is the client running?");
            ctx.out().println("Use 'connect scan <filter>' to search with a different filter.");
            lastScanResults = null;
            return;
        }
        if (pipes.size() == 1) {
            ctx.connect(pipes.getFirst());
            lastScanResults = null;
        } else {
            displayPipeSelection(pipes, ctx);
        }
    }

    private void scan(String filter, CliContext ctx) {
        String prefix = filter != null ? filter : "BotWithUs";
        ctx.out().println("Scanning for pipes matching '" + prefix + "'...");
        List<String> pipes = PipeClient.scanPipes(prefix);
        if (pipes.isEmpty()) {
            ctx.out().println("No matching pipes found.");
            lastScanResults = null;
            return;
        }
        displayPipeSelection(pipes, ctx);
    }

    /**
     * Probes each pipe for account info and displays a numbered selection table.
     * Pipes with no account info (e.g. Steam clients at login screen) are sent
     * to lobby first, then re-probed.
     */
    private void displayPipeSelection(List<String> pipes, CliContext ctx) {
        ctx.out().println("Found " + pipes.size() + " pipes. Probing for account info...");
        List<PipeInfo> infos = new ArrayList<>();
        List<String> needsLobby = new ArrayList<>();

        // First pass: probe all pipes
        for (String pipeName : pipes) {
            PipeInfo info = probePipe(pipeName);
            infos.add(info);
            if (info.displayName() == null || info.displayName().isEmpty()) {
                needsLobby.add(pipeName);
            }
        }

        // Second pass: send unknown clients to lobby and re-probe
        if (!needsLobby.isEmpty()) {
            ctx.out().println(needsLobby.size() + " client(s) have no account info — sending to lobby...");
            for (String pipeName : needsLobby) {
                PipeInfo updated = lobbyLoginAndProbe(pipeName, ctx);
                // Replace the entry in the list
                for (int i = 0; i < infos.size(); i++) {
                    if (infos.get(i).pipeName().equals(pipeName)) {
                        infos.set(i, updated);
                        break;
                    }
                }
            }
        }

        lastScanResults = infos;

        TableFormatter table = new TableFormatter().headers("#", "Pipe", "Account", "World", "Status");
        for (int i = 0; i < infos.size(); i++) {
            PipeInfo info = infos.get(i);
            String account = info.displayName() != null && !info.displayName().isEmpty()
                    ? info.displayName() : AnsiCodes.dim("(unknown)");
            String world = info.worldId() > 0
                    ? "W" + info.worldId() : "-";
            String status;
            if (info.loggedIn()) {
                status = AnsiCodes.colorize("Online", AnsiCodes.GREEN)
                        + (info.isMember() ? " " + AnsiCodes.colorize("[M]", AnsiCodes.YELLOW) : "");
            } else if (info.displayName() != null && !info.displayName().isEmpty()) {
                status = AnsiCodes.colorize("Lobby", AnsiCodes.CYAN);
            } else {
                status = AnsiCodes.dim("Offline");
            }
            table.row(String.valueOf(i + 1), info.pipeName(), account, world, status);
        }
        ctx.out().print(table.build());
        ctx.out().println("Use 'connect <number>' to connect, or 'connect --all' to connect to all.");
    }

    /**
     * Triggers {@code login_to_lobby} on a pipe, polls until account info
     * becomes available, then returns the probed info.
     */
    private PipeInfo lobbyLoginAndProbe(String pipeName, CliContext ctx) {
        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(5_000);

            // Trigger lobby login (only works from login screen, state 10)
            try {
                Map<String, Object> result = rpc.callSync("login_to_lobby", Map.of());
                // If server returned action: "new_game_session", need to retry after a delay
                if ("new_game_session".equals(getString(result, "action"))) {
                    ctx.out().println("  " + pipeName + ": new game session requested, retrying...");
                    Thread.sleep(2_000);
                    rpc.callSync("login_to_lobby", Map.of());
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("not_on_login_screen")) {
                    // Already past login screen — just probe directly
                    return probeWithRpc(pipeName, rpc);
                }
                ctx.out().println("  " + pipeName + ": lobby login failed — " + msg);
                return new PipeInfo(pipeName, null, -1, false, false);
            }

            // Poll until account info is available (display_name populated)
            ctx.out().println("  " + pipeName + ": waiting for lobby...");
            long deadline = System.currentTimeMillis() + LOBBY_POLL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    PipeInfo info = probeWithRpc(pipeName, rpc);
                    if (info.displayName() != null && !info.displayName().isEmpty()) {
                        ctx.out().println("  " + pipeName + ": " + info.displayName());
                        return info;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(LOBBY_POLL_INTERVAL_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            ctx.out().println("  " + pipeName + ": lobby timeout — could not retrieve account info.");
            return new PipeInfo(pipeName, null, -1, false, false);
        } catch (Exception e) {
            return new PipeInfo(pipeName, null, -1, false, false);
        }
    }

    /**
     * Opens a temporary connection to a pipe, queries account info, and closes it.
     */
    private PipeInfo probePipe(String pipeName) {
        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(3_000);
            return probeWithRpc(pipeName, rpc);
        } catch (Exception e) {
            return new PipeInfo(pipeName, null, -1, false, false);
        }
    }

    /**
     * Queries account info over an already-open RPC connection.
     */
    private PipeInfo probeWithRpc(String pipeName, RpcClient rpc) {
        try {
            Map<String, Object> r = rpc.callSync("get_account_info", Map.of());
            String displayName = getString(r, "display_name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(r, "jx_display_name");
            }
            boolean loggedIn = getBool(r, "logged_in");
            boolean isMember = getBool(r, "is_member");

            int worldId = -1;
            if (loggedIn) {
                try {
                    Map<String, Object> wr = rpc.callSync("get_current_world", Map.of());
                    worldId = getInt(wr, "world_id");
                } catch (Exception ignored) {}
            }

            return new PipeInfo(pipeName, displayName, worldId, loggedIn, isMember);
        } catch (Exception e) {
            // Fallback: try get_local_player
            try {
                Map<String, Object> r = rpc.callSync("get_local_player", Map.of());
                String name = getString(r, "name");
                return new PipeInfo(pipeName, name, -1, name != null && !name.isEmpty(), false);
            } catch (Exception ignored) {
                return new PipeInfo(pipeName, null, -1, false, false);
            }
        }
    }

    private void listConnections(CliContext ctx) {
        if (!ctx.hasConnections()) {
            ctx.out().println("No active connections.");
            return;
        }
        String active = ctx.getActiveConnectionName();
        TableFormatter table = new TableFormatter().headers("#", "Name", "Pipe", "Active");
        int i = 1;
        for (Connection conn : ctx.getConnections()) {
            String marker = conn.getName().equals(active)
                    ? AnsiCodes.colorize("*", AnsiCodes.GREEN) : "";
            table.row(String.valueOf(i++), conn.getName(), conn.getPipe().getPipePath(), marker);
        }
        ctx.out().print(table.build());
    }

    private void useConnection(String name, CliContext ctx) {
        if (name == null) {
            ctx.out().println("Usage: connect use <name>");
            return;
        }
        if (ctx.setActive(name)) {
            ctx.out().println("Active connection: " + name);
        } else {
            ctx.out().println("Connection not found: " + name);
        }
    }

    private void status(CliContext ctx) {
        if (!ctx.hasConnections()) {
            ctx.out().println("No connections.");
            return;
        }
        ctx.out().println("Connections: " + ctx.getConnections().size());
        ctx.out().println("Active: " + (ctx.getActiveConnectionName() != null ? ctx.getActiveConnectionName() : "none"));
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    private static boolean getBool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        return false;
    }
}
