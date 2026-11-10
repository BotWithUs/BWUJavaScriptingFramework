package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.report.ReportSubject;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Carries "Report a problem" from a script's own UI to the host's report
 * dialog. A script asks through its {@code ScriptUiHost} on its UI's render
 * thread, or on any thread of its own; the host's render thread hands the
 * request on once a frame, since the dialog is render thread only. Only the
 * newest request waiting is kept: two clicks in one frame open one dialog.
 *
 * <p>{@link #request} is safe from any thread; {@link #connect} and
 * {@link #deliver} are render thread only.</p>
 */
public final class ScriptReportRequests {

    private final AtomicReference<Optional<ReportSubject>> pending = new AtomicReference<>(Optional.empty());
    private volatile Optional<Consumer<ReportSubject>> opener = Optional.empty();

    /** A fresh set of requests: nothing connected, nothing waiting. (Explicit: the module's lints ask for it.) */
    public ScriptReportRequests() {
    }

    /** Sends every request from now on to {@code opener}, which opens the dialog. */
    public void connect(Consumer<ReportSubject> opener) {
        this.opener = Optional.of(Objects.requireNonNull(opener, "opener"));
    }

    /**
     * Asks for the dialog about {@code subject}'s script. False, and nothing
     * queued, when no dialog is connected or the script has no game client.
     */
    public boolean request(InspectorSubject subject) {
        Optional<ReportSubject> report = subjectOf(subject);
        if (opener.isEmpty() || report.isEmpty()) {
            return false;
        }
        pending.set(report);
        return true;
    }

    /** Opens the dialog for the newest request waiting, if any. Call once a frame. */
    public void deliver() {
        Optional<ReportSubject> report = pending.getAndSet(Optional.empty());
        opener.ifPresent(open -> report.ifPresent(open));
    }

    /** The report a subject's script can be the subject of: a client script on a named connection. */
    static Optional<ReportSubject> subjectOf(InspectorSubject subject) {
        return switch (subject) {
            case ClientScript s when !s.clientId().isBlank() ->
                    Optional.of(new ReportSubject(s.clientId(), s.scriptName()));
            case ClientScript _ -> Optional.empty();
            case ManagementScript _ -> Optional.empty();
        };
    }
}
