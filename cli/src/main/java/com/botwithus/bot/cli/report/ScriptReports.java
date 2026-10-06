package com.botwithus.bot.cli.report;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.report.CrashPayload;
import com.botwithus.bot.core.report.LauncherReportChannel;
import com.botwithus.bot.core.report.ReportReply;
import com.botwithus.bot.core.report.ReportRequest;
import com.botwithus.bot.core.runlog.CrashSummary;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;
import com.botwithus.bot.core.runlog.ScriptRun;
import com.botwithus.bot.core.runlog.ScriptSlug;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.shm.SharedRegion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sends a problem report about one script on one client: gathers what the host
 * knows (its run logs, its last crash, the names to hide, its Store id) into a
 * {@link ReportRequest} and hands it to the launcher.
 *
 * <p>One instance per host process, made at the composition root. The GUI's
 * dialog and the {@code report} command both go through it, so the two send the
 * same thing.</p>
 */
public final class ScriptReports implements ReportSender {

    private static final Logger log = LoggerFactory.getLogger(ScriptReports.class);

    private final Deps deps;

    /**
     * What a report is gathered from.
     *
     * @param runLogs     the process's run logs, read at each report
     * @param connections the host's connections, read at each report
     * @param sdnInstalls the Store install recorded for a script class, if any
     * @param host        this host's identity
     * @param channel     the way to the launcher
     */
    public record Deps(Supplier<RunLogs> runLogs, Supplier<Collection<Connection>> connections,
                       Function<String, Optional<InstalledSdnScript>> sdnInstalls, HostIdentity host,
                       LauncherReportChannel channel) {
        public Deps {
            Objects.requireNonNull(runLogs, "runLogs");
            Objects.requireNonNull(connections, "connections");
            Objects.requireNonNull(sdnInstalls, "sdnInstalls");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(channel, "channel");
        }
    }

    public ScriptReports(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps");
    }

    /**
     * Reports for this host process: {@code ctx}'s run logs and connections, the
     * launcher's directory from this process's environment.
     *
     * @param sdnInstalls the Store ledger's lookup by script class
     */
    public static ScriptReports forHost(CliContext ctx, Function<String, Optional<InstalledSdnScript>> sdnInstalls) {
        return new ScriptReports(new Deps(ctx::getRunLogs, ctx::getConnections, sdnInstalls,
                HostIdentity.current(ScriptReports.class), LauncherReportChannel.forThisProcess()));
    }

    /** {@link #previewNow} on a virtual thread: it lists the log directory. */
    @Override
    public CompletableFuture<ReportPreview> preview(ReportSubject subject) {
        CompletableFuture<ReportPreview> done = new CompletableFuture<>();
        Thread.ofVirtual().name("report-preview").start(() -> done.complete(previewNow(subject)));
        return done;
    }

    /** What the user is about to send, for the dialog's "What will be sent". Never throws. */
    public ReportPreview previewNow(ReportSubject subject) {
        try {
            return gatherPreview(subject);
        } catch (RuntimeException e) {
            log.warn("Could not list what a report for {} would send", subject.scriptName(), e);
            return new ReportPreview(subject.scriptName(), Optional.empty(), List.of());
        }
    }

    private ReportPreview gatherPreview(ReportSubject subject) {
        RunLogs logs = deps.runLogs().get();
        Optional<CrashSummary> crash = logs.lastCrash(subject.connectionName(), subject.scriptName());
        Optional<Path> logFile = crash.flatMap(CrashSummary::logFile)
                .or(() -> logs.currentOrLastLog(subject.connectionName(), subject.scriptName()));
        Optional<Path> dir = logFile.map(Path::getParent);
        return new ReportPreview(subject.scriptName(), crash.map(CrashSummary::exception),
                dir.map(ReportPreview::newestLogs).orElse(List.of()));
    }

    /** Whether the host has anything to report about this script: a runner, a log or a crash. */
    public boolean knows(ReportSubject subject) {
        RunLogs logs = deps.runLogs().get();
        return runner(subject).isPresent()
                || logs.currentOrLastLog(subject.connectionName(), subject.scriptName()).isPresent()
                || logs.lastCrash(subject.connectionName(), subject.scriptName()).isPresent();
    }

    /** The request for {@code subject} with the user's {@code note}. */
    public ReportRequest request(ReportSubject subject, String note) {
        RunLogs logs = deps.runLogs().get();
        String conn = subject.connectionName();
        String script = subject.scriptName();
        Optional<CrashSummary> crash = logs.lastCrash(conn, script);
        Optional<ScriptRunner> runner = runner(subject);
        Optional<Path> logFile = crash.flatMap(CrashSummary::logFile)
                .or(() -> logs.currentOrLastLog(conn, script));
        Optional<String> runId = crash.map(CrashSummary::runId)
                .or(() -> logs.openRun(conn, script).map(ScriptRun::runId))
                .or(() -> logFile.flatMap(RunLogHeaders::runId));
        return new ReportRequest(script, ScriptSlug.of(script), storeId(runner), version(runner),
                deps.host().hostVersion(), runId, logFile.map(Path::getParent), SharedRegion.parsePid(conn),
                connection(conn).map(Connection::knownNames).orElse(KnownNames.NONE),
                crash.map(CrashPayload::of), note);
    }

    /**
     * Sends the report on a virtual thread. The future always completes normally:
     * anything that goes wrong on the way is a {@link ReportReply.Failed} with a
     * message to show.
     */
    @Override
    public CompletableFuture<ReportReply> send(ReportSubject subject, String note) {
        CompletableFuture<ReportReply> done = new CompletableFuture<>();
        Thread.ofVirtual().name("report-send").start(() -> done.complete(sendNow(subject, note)));
        return done;
    }

    /** Sends the report on the calling thread, which waits up to the channel's windows. */
    public ReportReply sendNow(ReportSubject subject, String note) {
        try {
            ReportRequest request = request(subject, note);
            String id = deps.channel().newRequestId(ProcessHandle.current().pid(), request.runId());
            ReportReply reply = deps.channel().send(id, request);
            log.info("Report for {} ended: {}", subject.scriptName(), outcome(reply));
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReportReply.Failed.badReply();
        } catch (RuntimeException e) {
            log.warn("Could not send a report for {}", subject.scriptName(), e);
            return ReportReply.Failed.badReply();
        }
    }

    private static String outcome(ReportReply reply) {
        return switch (reply) {
            case ReportReply.Sent s -> "sent as " + s.code();
            case ReportReply.Failed f -> "failed (" + f.error() + ")";
        };
    }

    private Optional<Connection> connection(String name) {
        return deps.connections().get().stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    private Optional<ScriptRunner> runner(ReportSubject subject) {
        return connection(subject.connectionName())
                .flatMap(c -> Optional.ofNullable(c.getRuntime().findRunner(subject.scriptName())));
    }

    private static Optional<String> version(Optional<ScriptRunner> runner) {
        return runner.map(ScriptRunner::getManifest).map(ScriptManifest::version).filter(v -> !v.isBlank());
    }

    /** The website's id for a Store script: the recorded catalogue id when it is a positive number. */
    private OptionalLong storeId(Optional<ScriptRunner> runner) {
        return runner.map(r -> r.getScript().getClass().getName())
                .flatMap(deps.sdnInstalls())
                .map(InstalledSdnScript::catalogueId)
                .map(ScriptReports::positiveLong)
                .orElse(OptionalLong.empty());
    }

    private static OptionalLong positiveLong(String text) {
        try {
            long value = Long.parseLong(text.strip());
            return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
