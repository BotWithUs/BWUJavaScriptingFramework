package com.botwithus.bot.cli.gui.report;

import com.botwithus.bot.cli.gui.report.ReportFlow.Stage;
import com.botwithus.bot.cli.report.ReportForm;
import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.cli.report.ReportSender;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.core.report.ProblemKind;
import com.botwithus.bot.core.report.ReportReply;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportFlowTest {

    private static final ReportSubject AGILITY = new ReportSubject("BotWithUs_1", "Agility");
    private static final ReportSubject FISHING = new ReportSubject("BotWithUs_1", "Fishing");
    private static final String NOTE = "stuck at the wall by the river";
    private static final ReportForm COMPLETE = new ReportForm(Optional.of(ProblemKind.STUCK), NOTE);

    /** Hands back futures the test completes, and remembers what was sent. */
    private static final class Sender implements ReportSender {
        final CompletableFuture<ReportReply> reply = new CompletableFuture<>();
        final List<String> sent = new ArrayList<>();

        @Override
        public CompletableFuture<ReportPreview> preview(ReportSubject subject) {
            return CompletableFuture.completedFuture(new ReportPreview(subject.scriptName(), Optional.empty(),
                    List.of(), true));
        }

        @Override
        public CompletableFuture<ReportReply> send(ReportSubject subject, ProblemKind problem, String note) {
            sent.add(problem.wireName() + ": " + note);
            return reply;
        }
    }

    private final Sender sender = new Sender();
    private final ReportFlow flow = new ReportFlow(sender);

    @Test
    void walksComposeSendDone_andShowsTheReplyItGot() {
        flow.open(AGILITY);
        String composing = describe(flow.stage());
        flow.drawn();
        flow.send(COMPLETE);
        String sending = describe(flow.stage());
        ReportReply.Sent sent = new ReportReply.Sent("BWU-7K3Q9P", "Sent.");
        sender.reply.complete(sent);

        assertAll(
                () -> assertEquals("composing, new", composing),
                () -> assertEquals("sending Agility", sending),
                () -> assertEquals(new Stage.Done(AGILITY, sent), flow.stage()),
                () -> assertEquals(List.of("stuck: " + NOTE), sender.sent));
    }

    /** Send is off in the dialog until both answers are in; the flow refuses too, as a backstop. */
    @Test
    void anIncompleteForm_isNotSent() {
        flow.open(AGILITY);
        flow.send(new ReportForm(Optional.empty(), NOTE));
        flow.send(new ReportForm(Optional.of(ProblemKind.OTHER), "too short"));
        flow.send(new ReportForm(Optional.of(ProblemKind.OTHER), "              "));

        assertAll(
                () -> assertEquals(List.of(), sender.sent),
                () -> assertEquals("composing, new", describe(flow.stage())));
    }

    @Test
    void whileSending_neitherCloseNorOpenInterrupts() {
        flow.open(AGILITY);
        flow.send(COMPLETE);
        flow.close();
        flow.open(FISHING);

        assertEquals("sending Agility", describe(flow.stage()));
    }

    @Test
    void sendOutsideComposing_sendsNothing() {
        flow.send(COMPLETE);
        flow.open(AGILITY);
        flow.close();
        flow.send(COMPLETE);

        assertAll(
                () -> assertEquals(List.of(), sender.sent),
                () -> assertEquals(new Stage.Closed(), flow.stage()));
    }

    @Test
    void aSenderThatFails_endsInBadReply_notAnException() {
        flow.open(AGILITY);
        flow.send(COMPLETE);
        sender.reply.completeExceptionally(new IllegalStateException("boom"));

        assertEquals(new Stage.Done(AGILITY, ReportReply.Failed.badReply()), flow.stage());
    }

    @Test
    void drawn_clearsTheFirstFrameFlagOnce() {
        flow.open(AGILITY);
        flow.drawn();
        assertEquals("composing, drawn", describe(flow.stage()));
    }

    private static String describe(Stage stage) {
        return switch (stage) {
            case Stage.Closed _ -> "closed";
            case Stage.Composing c -> c.isNew() ? "composing, new" : "composing, drawn";
            case Stage.Sending s -> "sending " + s.subject().scriptName();
            case Stage.Done d -> "done " + d.subject().scriptName();
        };
    }
}
