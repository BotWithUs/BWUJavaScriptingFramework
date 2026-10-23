package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.cli.report.ReportSender;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.core.report.ProblemKind;
import com.botwithus.bot.core.report.ReportReply;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The report dialog's sender for the preview: a fixed "What will be sent", or
 * none at all for a script with no run log, and a reply chosen by the scenario,
 * or none, so the dialog stays on "Sending".
 */
final class FixtureReports implements ReportSender {

    /** A successful send, in the launcher's words (spec revision 5). */
    static final ReportReply SENT = new ReportReply.Sent("BWU-7K3Q9P", "Thanks! Your report has been sent to the "
            + "script's author. When they fix it, the fix will show in the launcher's News. "
            + "Your report code is BWU-7K3Q9P.");

    private final Optional<ReportReply> reply;
    private final boolean hasLogs;

    private FixtureReports(Optional<ReportReply> reply, boolean hasLogs) {
        this.reply = reply;
        this.hasLogs = hasLogs;
    }

    /** Answers every send with {@code reply}. */
    static FixtureReports answering(ReportReply reply) {
        return new FixtureReports(Optional.of(reply), true);
    }

    /** Never answers: the dialog stays on "Sending". */
    static FixtureReports pending() {
        return new FixtureReports(Optional.empty(), true);
    }

    /** A script the host has no run log for. */
    static FixtureReports noLogs() {
        return new FixtureReports(Optional.empty(), false);
    }

    @Override
    public CompletableFuture<ReportPreview> preview(ReportSubject subject) {
        if (!hasLogs) {
            return CompletableFuture.completedFuture(new ReportPreview(subject.scriptName(), Optional.empty(),
                    List.of(), false));
        }
        return CompletableFuture.completedFuture(new ReportPreview(subject.scriptName(),
                Optional.of("java.lang.NullPointerException: Cannot invoke \"Npc.name()\" because \"npc\" is null"),
                List.of("20261006-071223-3f9c1a2b.log", "20261006-064510-91d0e7c4.log",
                        "20261005-221802-5b7a20fe.log"), true));
    }

    @Override
    public CompletableFuture<ReportReply> send(ReportSubject subject, ProblemKind problem, String note) {
        return reply.map(CompletableFuture::completedFuture).orElseGet(CompletableFuture::new);
    }
}
