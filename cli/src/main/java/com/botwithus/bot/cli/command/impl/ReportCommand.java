package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandResult;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.cli.report.ScriptReports;
import com.botwithus.bot.core.report.ReportReply;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code report <script> [--note <text>] [--client <name>]}: sends a problem
 * report about a script to its author, exactly as the GUI's "Report a problem"
 * button does, and prints the launcher's answer. Blocks the command thread until
 * the launcher answers, which can take minutes.
 */
public class ReportCommand implements Command {

    private static final String NOTE = "note";
    private static final String CLIENT = "client";
    /** The value the parser gives a flag written without {@code =}. */
    private static final String BARE_FLAG = "true";

    private final ScriptReports reports;

    public ReportCommand(ScriptReports reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    @Override public String name() { return "report"; }
    @Override public String description() { return "Send a problem report about a script to its author"; }
    @Override public String usage() { return "report <script> [--note <text>] [--client=<name>]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        CommandResult result = executeWithResult(parsed, ctx);
        ctx.out().println(result.message());
    }

    @Override
    public CommandResult executeWithResult(ParsedCommand parsed, CliContext ctx) {
        String script = parsed.arg(0);
        if (script == null) {
            return CommandResult.error("Usage: " + usage());
        }
        Optional<String> client = subjectClient(parsed, ctx, script);
        if (client.isEmpty()) {
            return CommandResult.error("No client to report from. Connect one, or name it with --client=<name>.");
        }
        ReportSubject subject = new ReportSubject(client.get(), script);
        if (!reports.knows(subject)) {
            return CommandResult.error("Nothing is known about a script named '" + script + "' on "
                    + client.get() + ".");
        }
        ctx.out().println("Sending a report for " + script + "...");
        ReportReply reply = reports.sendNow(subject, note(parsed));
        return switch (reply) {
            case ReportReply.Sent s -> CommandResult.ok(s.userMessage());
            case ReportReply.Failed f -> CommandResult.error(f.userMessage());
        };
    }

    /**
     * {@code --note=<text>}, or {@code --note} followed by the words after the
     * script name: the parser keeps flag values only in the {@code =} form.
     */
    static String note(ParsedCommand parsed) {
        String flag = parsed.flag(NOTE);
        if (flag == null) {
            return "";
        }
        if (!flag.equals(BARE_FLAG)) {
            return flag;
        }
        List<String> args = parsed.args();
        return args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : "";
    }

    /**
     * The named client; else the one running the script, the active one first;
     * else the active one.
     */
    private static Optional<String> subjectClient(ParsedCommand parsed, CliContext ctx, String script) {
        String named = parsed.flag(CLIENT);
        if (named != null && !named.equals(BARE_FLAG)) {
            return Optional.of(named);
        }
        Optional<String> active = Optional.ofNullable(ctx.getActiveConnectionName());
        List<String> running = ctx.getConnections().stream()
                .filter(c -> c.getRuntime().findRunner(script) != null)
                .map(Connection::getName)
                .toList();
        if (active.isPresent() && running.contains(active.get())) {
            return active;
        }
        return running.isEmpty() ? active : Optional.of(running.getFirst());
    }
}
