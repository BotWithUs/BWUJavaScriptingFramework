package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.rpc.RpcMetrics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class MetricsCommand implements Command {

    @Override public String name() { return "metrics"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Show RPC call metrics"; }
    @Override public String usage() { return "metrics [reset] [--top=N]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection.");
            return;
        }

        RpcMetrics metrics = conn.getRpc().getMetrics();

        if ("reset".equals(parsed.arg(0))) {
            metrics.reset();
            ctx.out().println("Metrics reset.");
            return;
        }

        Map<String, RpcMetrics.MethodStats> snapshot = metrics.snapshot();
        if (snapshot.isEmpty()) {
            ctx.out().println("No metrics recorded yet.");
            return;
        }

        int top = parsed.intFlag("top", 0);

        var entries = snapshot.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, RpcMetrics.MethodStats>>comparingLong(e -> e.getValue().callCount()).reversed())
                .toList();

        if (top > 0 && top < entries.size()) {
            entries = entries.subList(0, top);
        }

        ctx.out().printf("%-35s %8s %10s %8s%n", "Method", "Calls", "Avg(ms)", "Errors");
        ctx.out().println("-".repeat(65));
        for (var entry : entries) {
            RpcMetrics.MethodStats stats = entry.getValue();
            ctx.out().printf("%-35s %8d %10.2f %8d%n",
                    entry.getKey(), stats.callCount(), stats.avgLatencyMs(), stats.errorCount());
        }
    }
}
