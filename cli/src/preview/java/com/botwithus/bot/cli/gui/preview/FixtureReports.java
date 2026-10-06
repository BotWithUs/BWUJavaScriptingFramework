package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.cli.report.ReportSender;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.core.report.ReportReply;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The report dialog's sender for the preview: a fixed "What will be sent" and a
 * reply chosen by the scenario, or none, so the dialog stays on "Sending".
 *
 * <p>The reply's wording is fixture text standing in for the launcher's; the
 * real wording is the launcher's to choose and the host shows it unchanged.</p>
 */
final class FixtureReports implements ReportSender {

    /** A successful send, as the launcher might word it. */
    static final ReportReply SENT = new ReportReply.Sent("BWU-7K3Q9P", Optional.empty(),
            "Thanks! Your report is with the script's author. If they ask, give them your report code.");

    private final Optional<ReportReply> reply;

    private FixtureReports(Optional<ReportReply> reply) {
        this.reply = reply;
    }

    /** Answers every send with {@code reply}. */
    static FixtureReports answering(ReportReply reply) {
        return new FixtureReports(Optional.of(reply));
    }

    /** Never answers: the dialog stays on "Sending". */
    static FixtureReports pending() {
        return new FixtureReports(Optional.empty());
    }

    @Override
    public CompletableFuture<ReportPreview> preview(ReportSubject subject) {
        return CompletableFuture.completedFuture(new ReportPreview(subject.scriptName(),
                Optional.of("java.lang.NullPointerException: Cannot invoke \"Npc.name()\" because \"npc\" is null"),
                List.of("20261006-071223-3f9c1a2b.log", "20261006-064510-91d0e7c4.log",
                        "20261005-221802-5b7a20fe.log")));
    }

    @Override
    public CompletableFuture<ReportReply> send(ReportSubject subject, String note) {
        return reply.map(CompletableFuture::completedFuture).orElseGet(CompletableFuture::new);
    }
}
