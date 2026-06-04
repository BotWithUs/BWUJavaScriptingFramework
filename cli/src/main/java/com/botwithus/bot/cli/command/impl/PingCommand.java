package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.impl.MapHelper;
import com.botwithus.bot.core.pipe.PipeException;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;

import java.util.List;
import java.util.Map;

public class PingCommand implements Command {

    @Override public String name() { return "ping"; }
    @Override public List<String> aliases() { return List.of(); }
    @Override public String description() { return "Test connection to game pipe with ping/pong"; }
    @Override public String usage() { return "ping"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        RpcClient rpc = conn.getRpc();
        long start = System.nanoTime();
        try {
            Map<String, Object> result = rpc.callSync("rpc.ping", Map.of());
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            boolean pong = MapHelper.getBool(result, "pong");
            if (pong) {
                ctx.out().println("Pong! (" + elapsed + "ms)");
            } else {
                ctx.out().println("Unexpected response: " + result + " (" + elapsed + "ms)");
            }
        } catch (PipeException | RpcException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            ctx.out().println("Ping failed (" + elapsed + "ms): " + e.getMessage());
            ctx.handleConnectionError(conn.getName());
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            ctx.out().println("Ping failed (" + elapsed + "ms): " + e.getMessage());
        }
    }
}
