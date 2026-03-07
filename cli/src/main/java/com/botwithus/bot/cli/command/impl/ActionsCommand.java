package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.rpc.RpcClient;

import java.util.List;
import java.util.Map;

public class ActionsCommand implements Command {

    @Override public String name() { return "actions"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Inspect the game action queue"; }
    @Override public String usage() { return "actions [queue | history [N] | blocked]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection.");
            return;
        }

        RpcClient rpc = conn.getRpc();
        String sub = parsed.arg(0);

        if (sub == null || "queue".equals(sub)) {
            showQueue(rpc, ctx);
        } else if ("history".equals(sub)) {
            int n = 10;
            String nStr = parsed.arg(1);
            if (nStr != null) {
                try { n = Integer.parseInt(nStr); } catch (NumberFormatException ignored) {}
            }
            showHistory(rpc, n, ctx);
        } else if ("blocked".equals(sub)) {
            showBlocked(rpc, ctx);
        } else {
            ctx.out().println("Unknown subcommand: " + sub + ". Use: queue, history, blocked");
        }
    }

    private void showQueue(RpcClient rpc, CliContext ctx) {
        try {
            Map<String, Object> result = rpc.callSync("rpc.getActionQueueSize", Map.of());
            ctx.out().println("Action queue size: " + result.getOrDefault("size", "unknown"));
        } catch (Exception e) {
            ctx.out().println("Error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void showHistory(RpcClient rpc, int max, CliContext ctx) {
        try {
            List<Map<String, Object>> history = rpc.callSyncList("rpc.getActionHistory",
                    Map.of("max_results", max));
            if (history.isEmpty()) {
                ctx.out().println("No action history.");
                return;
            }
            ctx.out().printf("%-12s %10s %10s %10s %12s%n", "Action ID", "Param1", "Param2", "Param3", "Delta(ms)");
            ctx.out().println("-".repeat(60));
            for (Map<String, Object> entry : history) {
                ctx.out().printf("%-12s %10s %10s %10s %12s%n",
                        entry.getOrDefault("action_id", ""),
                        entry.getOrDefault("param1", ""),
                        entry.getOrDefault("param2", ""),
                        entry.getOrDefault("param3", ""),
                        entry.getOrDefault("delta", ""));
            }
        } catch (Exception e) {
            ctx.out().println("Error: " + e.getMessage());
        }
    }

    private void showBlocked(RpcClient rpc, CliContext ctx) {
        try {
            Map<String, Object> result = rpc.callSync("rpc.areActionsBlocked", Map.of());
            boolean blocked = result.get("blocked") instanceof Boolean b && b;
            ctx.out().println("Actions blocked: " + blocked);
        } catch (Exception e) {
            ctx.out().println("Error: " + e.getMessage());
        }
    }
}
