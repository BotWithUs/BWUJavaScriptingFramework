package com.botwithus.bot.cli.report;

import com.botwithus.bot.core.report.ReportReply;

import java.util.concurrent.CompletableFuture;

/**
 * What the report dialog needs from the host. Both calls return at once and
 * finish off the calling thread, so the render thread can make them.
 */
public interface ReportSender {

    /** What a report about {@code subject} would carry; never fails. */
    CompletableFuture<ReportPreview> preview(ReportSubject subject);

    /** Sends the report; always completes normally, with the outcome to show. */
    CompletableFuture<ReportReply> send(ReportSubject subject, String note);
}
