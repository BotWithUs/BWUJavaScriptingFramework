package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.OutputLine;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.core.pipe.PipeException;
import com.botwithus.bot.core.rpc.RpcException;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The console behind the Dashboard's Console tab: an output buffer every command
 * prints to, and a command registry whose commands run one at a time on the
 * host's command thread, never on the render thread.
 */
public final class CommandConsole {

    private final AnsiOutputBuffer output;
    private final CommandRegistry registry;
    private final ExecutorService executor;
    private final CliContext ctx;
    private final Runnable shutdownHook;

    /**
     * @param executor     the host's single command thread
     * @param shutdownHook what a command that asks to quit ({@code exit}) runs
     */
    public CommandConsole(AnsiOutputBuffer output, CommandRegistry registry, ExecutorService executor,
                          CliContext ctx, Runnable shutdownHook) {
        this.output = output;
        this.registry = registry;
        this.executor = executor;
        this.ctx = ctx;
        this.shutdownHook = shutdownHook;
    }

    /** The console's output, oldest first. */
    public List<OutputLine> lines() {
        return output.snapshot();
    }

    /** Where commands and console notes print. */
    public PrintStream out() {
        return output.getPrintStream();
    }

    /** Every command name and alias, for Tab completion. */
    public List<String> commandNames() {
        List<String> names = new ArrayList<>();
        for (Command cmd : registry.all()) {
            names.add(cmd.name());
            names.addAll(cmd.aliases());
        }
        return names;
    }

    /**
     * Echoes {@code line} and runs it on the command thread.
     *
     * @return completes when the command has finished
     */
    public Future<?> submit(String line) {
        PrintStream out = out();
        out.println(AnsiCodes.colorize("> " + line, AnsiCodes.YELLOW));
        return executor.submit(() -> run(line, out));
    }

    private void run(String line, PrintStream out) {
        ParsedCommand parsed = CommandParser.parse(line);
        Command cmd = registry.resolve(parsed.name());
        if (cmd == null) {
            out.println("Unknown command: " + parsed.name() + ". Type 'help' for available commands.");
            return;
        }
        if (cmd.requestsShutdown()) {
            shutdownHook.run();
            return;
        }
        try {
            cmd.execute(parsed, ctx);
        } catch (PipeException | RpcException e) {
            out.println("Connection error: " + e.getMessage());
            String connName = ctx.getActiveConnectionName();
            if (connName != null) {
                ctx.handleConnectionError(connName);
            }
        } catch (RuntimeException e) {
            out.println("Error: " + e.getMessage());
        }
    }
}
