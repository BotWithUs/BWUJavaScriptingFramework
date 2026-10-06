package com.botwithus.bot.cli.gui.report;

import com.botwithus.bot.cli.gui.report.ReportFlow.Stage;
import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.cli.report.ReportSender;
import com.botwithus.bot.cli.report.ReportSubject;
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

    /** Hands back futures the test completes, and remembers what was sent. */
    private static final class Sender implements ReportSender {
        final CompletableFuture<ReportReply> reply = new CompletableFuture<>();
        final List<String> notes = new ArrayList<>();

        @Override
        public CompletableFuture<ReportPreview> preview(ReportSubject subject) {
            return CompletableFuture.completedFuture(new ReportPreview(subject.scriptName(), Optional.empty(),
                    List.of()));
        }

        @Override
        public CompletableFuture<ReportReply> send(ReportSubject subject, String note) {
            notes.add(note);
            return reply;
        }
    }

    private final Sender sender = new Sender();
    private final ReportFlow flow = new ReportFlow(sender);

    @Test
    void walksComposeSendDone_andShowsTheReplyItGot() {
        flow.open(AGILITY);
        Stage composing = flow.stage();
        flow.drawn();
        flow.send("stuck at the wall");
        Stage sending = flow.stage();
        ReportReply.Sent sent = new ReportReply.Sent("BWU-7K3Q9P", Optional.empty(), "Sent.");
        sender.reply.complete(sent);

        assertAll(
                () -> assertEquals("composing, new", describe(composing)),
                () -> assertEquals("sending Agility", describe(sending)),
                () -> assertEquals(new Stage.Done(AGILITY, sent), flow.stage()),
                () -> assertEquals(List.of("stuck at the wall"), sender.notes));
    }

    @Test
    void whileSending_neitherCloseNorOpenInterrupts() {
        flow.open(AGILITY);
        flow.send("");
        flow.close();
        flow.open(FISHING);

        assertEquals("sending Agility", describe(flow.stage()));
    }

    @Test
    void sendOutsideComposing_sendsNothing() {
        flow.send("x");
        flow.open(AGILITY);
        flow.close();
        flow.send("y");

        assertAll(
                () -> assertEquals(List.of(), sender.notes),
                () -> assertEquals(new Stage.Closed(), flow.stage()));
    }

    @Test
    void aSenderThatFails_endsInBadReply_notAnException() {
        flow.open(AGILITY);
        flow.send("");
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
