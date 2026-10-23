package com.botwithus.bot.cli.gui.report;

import com.botwithus.bot.cli.report.ReportForm;
import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.cli.report.ReportSender;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.core.report.ReportReply;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Where the "Report a problem" dialog stands, apart from drawing it: closed,
 * writing a note, sending, or showing the answer. The crash toast and the
 * script rows {@link #open} it; {@link ReportDialog} draws {@link #stage()}.
 *
 * <p>Render thread only. The slow work runs on the {@link ReportSender}'s
 * threads; this only looks at whether it has finished.</p>
 */
public final class ReportFlow {

    /** What the dialog shows. */
    public sealed interface Stage {

        /** Nothing on screen. */
        record Closed() implements Stage { }

        /**
         * Asking for a note. {@code preview} fills "What will be sent" when ready.
         *
         * @param isNew whether this stage has not been drawn yet, for the first-frame focus
         */
        record Composing(ReportSubject subject, CompletableFuture<ReportPreview> preview, boolean isNew)
                implements Stage {
            public Composing {
                Objects.requireNonNull(subject, "subject");
                Objects.requireNonNull(preview, "preview");
            }

            /** The preview if it has arrived. */
            public Optional<ReportPreview> previewIfReady() {
                return Optional.ofNullable(preview.getNow(null));
            }
        }

        /** Waiting for the launcher. */
        record Sending(ReportSubject subject, CompletableFuture<ReportReply> reply) implements Stage {
            public Sending {
                Objects.requireNonNull(subject, "subject");
                Objects.requireNonNull(reply, "reply");
            }
        }

        /** The launcher answered, or the host gave up waiting. */
        record Done(ReportSubject subject, ReportReply reply) implements Stage {
            public Done {
                Objects.requireNonNull(subject, "subject");
                Objects.requireNonNull(reply, "reply");
            }
        }
    }

    private final ReportSender sender;
    private Stage stage = new Stage.Closed();

    public ReportFlow(ReportSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /**
     * Opens the dialog about {@code subject}. Ignored while a report is sending:
     * that one keeps the dialog until it is answered.
     */
    public void open(ReportSubject subject) {
        switch (stage) {
            case Stage.Sending _ -> { }
            case Stage.Closed _, Stage.Composing _, Stage.Done _ ->
                    stage = new Stage.Composing(subject, sender.preview(subject), true);
        }
    }

    /**
     * Sends what is being composed. Ignored in any other stage, and while the form
     * is incomplete: the dialog keeps Send off then, and this is its backstop.
     */
    public void send(ReportForm form) {
        switch (stage) {
            case Stage.Composing c when form.isComplete() -> stage = new Stage.Sending(c.subject(),
                    sender.send(c.subject(), form.problem().orElseThrow(), form.note()));
            case Stage.Composing _, Stage.Closed _, Stage.Sending _, Stage.Done _ -> { }
        }
    }

    /** Closes the dialog, unless a report is sending. */
    public void close() {
        switch (stage) {
            case Stage.Sending _ -> { }
            case Stage.Closed _, Stage.Composing _, Stage.Done _ -> stage = new Stage.Closed();
        }
    }

    /** The current stage; moves a finished send on to {@link Stage.Done}. */
    public Stage stage() {
        switch (stage) {
            case Stage.Sending s when s.reply().isDone() -> stage = new Stage.Done(s.subject(), s.reply()
                    .handle((reply, error) -> reply != null ? reply : ReportReply.Failed.badReply())
                    .join());
            case Stage.Closed _, Stage.Composing _, Stage.Sending _, Stage.Done _ -> { }
        }
        return stage;
    }

    /** Marks the composing stage as drawn once. */
    void drawn() {
        switch (stage) {
            case Stage.Composing c when c.isNew() ->
                    stage = new Stage.Composing(c.subject(), c.preview(), false);
            case Stage.Closed _, Stage.Composing _, Stage.Sending _, Stage.Done _ -> { }
        }
    }
}
