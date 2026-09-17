package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import java.util.List;

public class StreamCommand implements Command {

    private static final int DEFAULT_QUALITY = 60;
    private static final int DEFAULT_FRAME_SKIP = 2;
    private static final int DEFAULT_WIDTH = 960;
    private static final int DEFAULT_HEIGHT = 540;

    @Override public String name() { return "stream"; }
    @Override public List<String> aliases() { return List.of("sv"); }
    @Override public String description() { return "Start or stop live game video streaming"; }
    @Override public String usage() { return "stream <start|stop> [--group <name>] [--quality 60] [--fps 2] [--width 960] [--height 540]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String action = parsed.arg(0);
        if (action == null) {
            ctx.out().println("Usage: " + usage());
            return;
        }

        switch (action.toLowerCase()) {
            case "start" -> handleStart(parsed, ctx);
            case "stop" -> handleStop(parsed, ctx);
            default -> ctx.out().println("Unknown action: " + action + ". Use 'start' or 'stop'.");
        }
    }

    private void handleStart(ParsedCommand parsed, CliContext ctx) {
        int quality = parsed.intFlag("quality", DEFAULT_QUALITY);
        int frameSkip = parsed.intFlag("fps", DEFAULT_FRAME_SKIP);
        int width = parsed.intFlag("width", DEFAULT_WIDTH);
        int height = parsed.intFlag("height", DEFAULT_HEIGHT);

        String groupName = parsed.flag("group");
        if (groupName != null) {
            List<Connection> conns = ctx.getGroupConnections(groupName);
            if (conns.isEmpty()) {
                ctx.out().println("Group '" + groupName + "' has no active connections.");
                return;
            }
            for (Connection conn : conns) {
                ctx.getStreamManager().startStream(conn, quality, frameSkip, width, height);
            }
        } else {
            Connection conn = ctx.getActiveConnection();
            if (conn == null) {
                ctx.out().println("No active connection. Use 'connect' first.");
                return;
            }
            ctx.getStreamManager().startStream(conn, quality, frameSkip, width, height);
        }
    }

    private void handleStop(ParsedCommand parsed, CliContext ctx) {
        ctx.getStreamManager().stopAll(name -> {
            for (Connection c : ctx.getConnections()) {
                if (c.getName().equals(name)) {
                    return c;
                }
            }
            return null;
        });
    }
}
