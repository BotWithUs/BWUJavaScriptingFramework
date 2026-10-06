package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandResult;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.cli.report.ScriptReports;
import com.botwithus.bot.core.report.ProblemKind;
import com.botwithus.bot.core.report.ReportReply;
import com.botwithus.bot.core.report.ReportRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code report <script> --problem <kind> --note <text> [--client=<name>]}: sends
 * a problem report about a script to its author, exactly as the GUI's "Report a
 * problem" does, and prints the launcher's answer. Both the problem and a note of
 * {@value ReportRequest#MIN_NOTE_CHARS} characters or more are required, and a
 * script with no run log is not reported. Blocks the command thread until the
 * launcher answers, which can take minutes.
 */
public class ReportCommand implements Command {

    static final String USAGE = "report <script> --problem <crashed|stuck|wrong_action|other> "
            + "--note <what you were doing> [--client=<name>]";

    private static final String PROBLEM = "problem";
    private static final String NOTE = "note";
    private static final String CLIENT = "client";
    /** The value the parser gives a flag written without {@code =}. */
    private static final String BARE_FLAG = "true";

    private final ScriptReports reports;

    /**
     * The command line read into a report's answers.
     *
     * @param script  the script name
     * @param problem what went wrong, when given and spelled right
     * @param note    what the user was doing; blank when not given
     */
    record Args(Optional<String> script, Optional<ProblemKind> problem, String note) {

        /**
         * Reads {@code --problem=<kind>} or {@code --problem <kind>}, and
         * {@code --note=<text>} or {@code --note} followed by the words after the
         * rest. The parser keeps a flag's value only in the {@code =} form, so a
         * bare {@code --problem} takes the first word after the script name, and a
         * bare {@code --note} takes every word after that.
         */
        static Args of(ParsedCommand parsed) {
            List<String> args = parsed.args();
            String problemFlag = parsed.flag(PROBLEM);
            boolean isBareProblem = BARE_FLAG.equals(problemFlag);
            int wordsFrom = isBareProblem ? 2 : 1;
            Optional<String> problemWord = isBareProblem
                    ? Optional.ofNullable(parsed.arg(1))
                    : Optional.ofNullable(problemFlag);
            return new Args(Optional.ofNullable(parsed.arg(0)), problemWord.flatMap(ProblemKind::fromWire),
                    note(parsed.flag(NOTE), args, wordsFrom));
        }

        private static String note(String flag, List<String> args, int wordsFrom) {
            if (flag == null) {
                return "";
            }
            if (!flag.equals(BARE_FLAG)) {
                return flag;
            }
            return args.size() > wordsFrom ? String.join(" ", args.subList(wordsFrom, args.size())) : "";
        }
    }

    public ReportCommand(ScriptReports reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    @Override public String name() { return "report"; }
    @Override public String description() { return "Send a problem report about a script to its author"; }
    @Override public String usage() { return USAGE; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        CommandResult result = executeWithResult(parsed, ctx);
        ctx.out().println(result.message());
    }

    @Override
    public CommandResult executeWithResult(ParsedCommand parsed, CliContext ctx) {
        Args args = Args.of(parsed);
        if (args.script().isEmpty() || args.problem().isEmpty() || args.note().isBlank()) {
            return CommandResult.error("Usage: " + USAGE);
        }
        if (!ReportRequest.isNoteLongEnough(args.note())) {
            return CommandResult.error(ReportReply.Failed.noteTooShort().userMessage());
        }
        String script = args.script().get();
        Optional<String> client = subjectClient(parsed, ctx, script);
        if (client.isEmpty()) {
            return CommandResult.error("No client to report from. Connect one, or name it with --client=<name>.");
        }
        ReportSubject subject = new ReportSubject(client.get(), script);
        if (!reports.knows(subject)) {
            return CommandResult.error("Nothing is known about a script named '" + script + "' on "
                    + client.get() + ".");
        }
        if (!reports.hasLogs(subject)) {
            return CommandResult.error(ReportReply.Failed.logsMissing().userMessage());
        }
        ctx.out().println("Sending a report for " + script + "...");
        return switch (reports.sendNow(subject, args.problem().get(), args.note())) {
            case ReportReply.Sent s -> CommandResult.ok(s.userMessage());
            case ReportReply.Failed f -> CommandResult.error(f.userMessage());
        };
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
