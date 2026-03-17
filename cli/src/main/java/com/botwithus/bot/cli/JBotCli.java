package com.botwithus.bot.cli;

import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.command.impl.*;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class JBotCli {

    private static final String BANNER = """

            ____        _ __        ___ _   _     _   _
           | __ )  ___ | |\\ \\      / (_) |_| |__ | | | |___
           |  _ \\ / _ \\| __\\ \\ /\\ / /| | __| '_ \\| | | / __|
           | |_) | (_) | |_ \\ V  V / | | |_| | | | |_| \\__ \\
           |____/ \\___/ \\__| \\_/\\_/  |_|\\__|_| |_|\\___/|___/
                        Script Manager

              Type 'help' for available commands.
            """;

    public static void main(String[] args) {
        // Force UTF-8 output on Windows
        PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream utf8Err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.setOut(utf8Out);
        System.setErr(utf8Err);

        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer);
        logCapture.install();

        PrintStream out = logCapture.getOriginalOut();
        CliContext ctx = new CliContext(logBuffer, logCapture);
        ctx.loadGroups();
        CommandRegistry registry = new CommandRegistry();

        // Register commands
        registry.register(new HelpCommand(registry));
        registry.register(new ConnectCommand());
        registry.register(new PingCommand());
        registry.register(new ScriptsCommand());
        registry.register(new LogsCommand());
        registry.register(new ReloadCommand());
        registry.register(new ScreenshotCommand());
        registry.register(new MountCommand());
        registry.register(new UnmountCommand());
        registry.register(new MetricsCommand());
        registry.register(new ProfileCommand());
        registry.register(new ConfigCommand(com.botwithus.bot.cli.config.CliConfig.defaults()));
        registry.register(new ActionsCommand());
        registry.register(new EventsCommand());
        registry.register(new ClearCommand());
        registry.register(new ExitCommand());

        // Print banner
        out.println(AnsiCodes.colorize(BANNER, AnsiCodes.CYAN));

        // REPL loop
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String connLabel;
            if (ctx.hasActiveConnection()) {
                String name = ctx.getActiveConnectionName();
                int count = ctx.getConnections().size();
                String suffix = count > 1 ? " [" + count + "]" : "";
                String mountIndicator = ctx.isMounted()
                        ? " " + AnsiCodes.colorize("[mounted]", AnsiCodes.MAGENTA) : "";
                connLabel = AnsiCodes.colorize("*", AnsiCodes.GREEN) + " "
                        + AnsiCodes.bold("bwu") + ":" + AnsiCodes.colorize(name, AnsiCodes.CYAN) + suffix + mountIndicator;
            } else {
                connLabel = AnsiCodes.colorize("o", AnsiCodes.RED) + " " + AnsiCodes.bold("bwu");
            }
            out.print(connLabel + "> ");
            out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            ParsedCommand parsed = CommandParser.parse(line);
            Command cmd = registry.resolve(parsed.name());
            if (cmd == null) {
                out.println("Unknown command: " + parsed.name() + ". Type 'help' for available commands.");
                continue;
            }

            try {
                cmd.execute(parsed, ctx);
            } catch (com.botwithus.bot.core.pipe.PipeException | com.botwithus.bot.core.rpc.RpcException e) {
                out.println("Connection error: " + e.getMessage());
                String connName = ctx.getActiveConnectionName();
                if (connName != null) {
                    ctx.handleConnectionError(connName);
                }
            } catch (Exception e) {
                out.println("Error: " + e.getMessage());
            }
        }
    }
}
