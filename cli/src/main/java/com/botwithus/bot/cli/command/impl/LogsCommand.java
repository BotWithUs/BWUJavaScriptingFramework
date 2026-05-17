package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.log.LogEntry;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

public class LogsCommand implements Command {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int DEFAULT_TAIL_LINES = 50;
    private static final int FOLLOW_POLL_INTERVAL_MS = 500;

    @Override public String name() { return "logs"; }
    @Override public List<String> aliases() { return List.of("log"); }
    @Override public String description() { return "View captured logs"; }
    @Override public String usage() { return "logs [--lines=N] [--filter=source] [--follow]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        int lines = parsed.intFlag("lines", DEFAULT_TAIL_LINES);
        String filter = parsed.flag("filter");
        boolean follow = parsed.hasFlag("follow");

        List<LogEntry> entries = ctx.getLogBuffer().tail(lines);
        if (filter != null) {
            entries = entries.stream()
                    .filter(e -> e.source().equalsIgnoreCase(filter) || e.message().contains(filter))
                    .toList();
        }
        // When mounted, only show entries from that connection (or system entries with null connection)
        if (ctx.isMounted()) {
            String mounted = ctx.getMountedConnectionName();
            entries = entries.stream()
                    .filter(e -> e.connection() == null || mounted.equals(e.connection()))
                    .toList();
        }

        boolean showConnection = !ctx.isMounted();
        for (LogEntry entry : entries) {
            ctx.out().println(formatEntry(entry, showConnection));
        }

        if (follow) {
            ctx.out().println(AnsiCodes.dim("-- Follow mode. Press Enter to exit --"));
            Instant cursor = Instant.now();
            Thread follower = Thread.ofVirtual().start(() -> {
                try {
                    Instant[] lastCheck = {cursor};
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(FOLLOW_POLL_INTERVAL_MS);
                        List<LogEntry> newEntries = ctx.getLogBuffer().since(lastCheck[0]);
                        String f = filter;
                        if (f != null) {
                            newEntries = newEntries.stream()
                                    .filter(e -> e.source().equalsIgnoreCase(f) || e.message().contains(f))
                                    .toList();
                        }
                        for (LogEntry entry : newEntries) {
                            ctx.out().println(formatEntry(entry, !ctx.isMounted()));
                        }
                        if (!newEntries.isEmpty()) {
                            lastCheck[0] = Instant.now();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Block until user presses Enter
            new Scanner(System.in).nextLine();
            follower.interrupt();
        }
    }

    private String formatEntry(LogEntry entry, boolean showConnection) {
        String time = TIME_FMT.format(entry.timestamp());
        String levelColor = "ERROR".equals(entry.level()) ? AnsiCodes.RED : AnsiCodes.CYAN;
        String connPrefix = "";
        if (showConnection && entry.connection() != null) {
            connPrefix = AnsiCodes.colorize("[" + entry.connection() + "]", AnsiCodes.MAGENTA) + " ";
        }
        return AnsiCodes.dim(time) + " "
                + AnsiCodes.colorize(entry.level(), levelColor) + " "
                + connPrefix
                + AnsiCodes.colorize("[" + entry.source() + "]", AnsiCodes.YELLOW) + " "
                + entry.message();
    }
}
