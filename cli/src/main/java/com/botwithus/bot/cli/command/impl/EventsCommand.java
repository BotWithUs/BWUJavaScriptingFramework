package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.impl.EventBusImpl;

import java.util.List;
import java.util.Map;

public class EventsCommand implements Command {

    @Override public String name() { return "events"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Monitor event bus activity"; }
    @Override public String usage() { return "events [subscriptions | counts | reset]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection.");
            return;
        }

        EventBusImpl eventBus = conn.getEventBus();
        if (eventBus == null) {
            ctx.out().println("Event bus not available for this connection.");
            return;
        }

        String sub = parsed.arg(0);

        if (sub == null || "subscriptions".equals(sub)) {
            Map<String, Integer> subs = eventBus.getSubscriptionInfo();
            if (subs.isEmpty()) {
                ctx.out().println("No event subscriptions.");
                return;
            }
            ctx.out().printf("%-30s %10s%n", "Event Type", "Listeners");
            ctx.out().println("-".repeat(42));
            for (var entry : subs.entrySet()) {
                ctx.out().printf("%-30s %10d%n", entry.getKey(), entry.getValue());
            }
        } else if ("counts".equals(sub)) {
            Map<String, Long> counts = eventBus.getEventCounts();
            if (counts.isEmpty()) {
                ctx.out().println("No events published yet.");
                return;
            }
            ctx.out().printf("%-30s %10s%n", "Event Type", "Count");
            ctx.out().println("-".repeat(42));
            for (var entry : counts.entrySet()) {
                ctx.out().printf("%-30s %10d%n", entry.getKey(), entry.getValue());
            }
        } else if ("reset".equals(sub)) {
            eventBus.resetCounts();
            ctx.out().println("Event counts reset.");
        } else {
            ctx.out().println("Unknown subcommand: " + sub);
        }
    }
}
