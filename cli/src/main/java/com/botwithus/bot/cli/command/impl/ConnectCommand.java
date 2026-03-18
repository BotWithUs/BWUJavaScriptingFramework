package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandResult;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;

import com.botwithus.bot.cli.AutoStartManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ConnectCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ConnectCommand.class);

    @Override public String name() { return "connect"; }
    @Override public List<String> aliases() { return List.of("conn"); }
    @Override public String description() { return "Manage pipe connections"; }
    @Override public String usage() { return "connect [<pipe>|<number>|scan [filter]|disconnect [name|--all]|list|use <name>|status]"; }

    private static final int LOBBY_POLL_INTERVAL_MS = 500;
    private static final int LOBBY_POLL_TIMEOUT_MS = 15_000;

    /** Info gathered from probing a pipe. */
    public record PipeInfo(String pipeName, String displayName, int worldId, boolean loggedIn, boolean isMember) {}

    /** Cached results from the last scan/autoConnect for number-based selection. */
    private List<PipeInfo> lastScanResults;

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        executeWithResult(parsed, ctx);
    }

    @Override
    public CommandResult executeWithResult(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);
        if (sub == null) {
            return autoConnect(ctx);
        } else {
            return switch (sub.toLowerCase()) {
                case "scan" -> scan(parsed.arg(1), ctx);
                case "disconnect", "dc" -> {
                    boolean force = parsed.hasFlag("force");
                    if (parsed.hasFlag("all")) {
                        ctx.disconnectAll(force);
                    } else {
                        ctx.disconnect(parsed.arg(1), force);
                    }
                    yield CommandResult.ok();
                }
                case "reconnect", "rc" -> {
                    String name = ctx.getActiveConnectionName();
                    ctx.disconnect(null);
                    ctx.connect(name);
                    yield CommandResult.ok();
                }
                case "list", "ls" -> {
                    listConnections(ctx);
                    yield CommandResult.ok();
                }
                case "use" -> {
                    useConnection(parsed.arg(1), ctx);
                    yield CommandResult.ok();
                }
                case "status" -> {
                    status(ctx);
                    yield CommandResult.ok();
                }
                default -> {
                    // Check if it's a number referencing a previous scan result
                    if (lastScanResults != null && isNumber(sub)) {
                        int index = Integer.parseInt(sub) - 1;
                        if (index >= 0 && index < lastScanResults.size()) {
                            String pipeName = lastScanResults.get(index).pipeName();
                            ctx.connect(pipeName);
                            probeAndAutoStart(pipeName, ctx);
                        } else {
                            ctx.out().println("Invalid selection. Choose 1-" + lastScanResults.size() + ".");
                        }
                    } else {
                        ctx.connect(sub);
                        probeAndAutoStart(sub, ctx);
                    }
                    yield CommandResult.ok();
                }
            };
        }
    }

    private CommandResult autoConnect(CliContext ctx) {
        List<String> pipes = PipeClient.scanPipes("BotWithUs");
        if (pipes.isEmpty()) {
            ctx.out().println("No BotWithUs pipes found. Is the client running?");
            ctx.out().println("Use 'connect scan <filter>' to search with a different filter.");
            lastScanResults = null;
            return CommandResult.error("No BotWithUs pipes found.");
        }
        if (pipes.size() == 1) {
            ctx.connect(pipes.getFirst());
            probeAndAutoStart(pipes.getFirst(), ctx);
            lastScanResults = null;
            return CommandResult.ok("Connected to " + pipes.getFirst());
        } else {
            return displayPipeSelection(pipes, ctx);
        }
    }

    private CommandResult scan(String filter, CliContext ctx) {
        String prefix = filter != null ? filter : "BotWithUs";
        ctx.out().println("Scanning for pipes matching '" + prefix + "'...");
        List<String> pipes = PipeClient.scanPipes(prefix);
        if (pipes.isEmpty()) {
            ctx.out().println("No matching pipes found.");
            lastScanResults = null;
            return CommandResult.error("No matching pipes found.");
        }
        return displayPipeSelection(pipes, ctx);
    }

    /**
     * Probes each pipe for account info and displays a numbered selection table.
     * Pipes with no account info (e.g. Steam clients at login screen) are sent
     * to lobby first, then re-probed.
     */
    private CommandResult displayPipeSelection(List<String> pipes, CliContext ctx) {
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
        return CommandResult.ok("Found " + infos.size() + " pipe(s).", Map.of("scanResults", List.copyOf(infos)));
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
                } catch (Exception e) {
                    log.warn("Lobby probe poll failed for {}: {}", pipeName, e.getMessage());
                }
                try { Thread.sleep(LOBBY_POLL_INTERVAL_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            ctx.out().println("  " + pipeName + ": lobby timeout — could not retrieve account info.");
            return new PipeInfo(pipeName, null, -1, false, false);
        } catch (Exception e) {
            log.error("lobbyLoginAndProbe failed for {}", pipeName, e);
            return new PipeInfo(pipeName, null, -1, false, false);
        }
    }

    /**
     * Opens a temporary connection to a pipe, queries account info, and closes it.
     * Uses a 5-second timeout to avoid blocking indefinitely if the pipe server is full.
     */
    private PipeInfo probePipe(String pipeName) {
        try {
            return CompletableFuture.supplyAsync(() -> probePipeBlocking(pipeName), java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("probePipe timed out for {}", pipeName);
            return new PipeInfo(pipeName, "(timeout)", -1, false, false);
        } catch (Exception e) {
            log.error("probePipe failed for {}", pipeName, e);
            return new PipeInfo(pipeName, null, -1, false, false);
        }
    }

    private PipeInfo probePipeBlocking(String pipeName) {
        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(3_000);
            return probeWithRpc(pipeName, rpc);
        } catch (Exception e) {
            log.error("probePipe failed for {}", pipeName, e);
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
                } catch (Exception e) {
                    log.warn("Failed to get world for {}: {}", pipeName, e.getMessage());
                }
            }

            return new PipeInfo(pipeName, displayName, worldId, loggedIn, isMember);
        } catch (Exception e) {
            // Fallback: try get_local_player
            log.warn("get_account_info failed for {}: {}", pipeName, e.getMessage());
            try {
                Map<String, Object> r = rpc.callSync("get_local_player", Map.of());
                String name = getString(r, "name");
                return new PipeInfo(pipeName, name, -1, name != null && !name.isEmpty(), false);
            } catch (Exception e2) {
                log.warn("get_local_player fallback also failed for {}: {}", pipeName, e2.getMessage());
                return new PipeInfo(pipeName, null, -1, false, false);
            }
        }
    }

    /**
     * After a successful connect, probes for account info and triggers auto-start
     * if an AutoStartManager is configured.
     */
    private void probeAndAutoStart(String connName, CliContext ctx) {
        AutoStartManager asm = ctx.getAutoStartManager();
        if (asm == null) return;

        // Find the connection that was just created
        Connection found = null;
        for (Connection c : ctx.getConnections()) {
            if (c.getName().equals(connName)) {
                found = c;
                break;
            }
        }
        if (found == null) return;
        final Connection conn = found;

        // Wire the state-change callback for auto-saving
        conn.getRuntime().setOnStateChange(() -> asm.saveState(conn));

        try {
            Map<String, Object> info = conn.getRpc().callSync("get_account_info", Map.of());
            String displayName = getString(info, "display_name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(info, "jx_display_name");
            }
            if (displayName != null && !displayName.isEmpty()) {
                conn.setAccountName(displayName);
                conn.setAccountInfo(info);
                ctx.out().println("Account: " + displayName);
                asm.onConnectionEstablished(conn, displayName);
            }
        } catch (Exception e) {
            log.error("probeAndAutoStart failed for {}", connName, e);
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
