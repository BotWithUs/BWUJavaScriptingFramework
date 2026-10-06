package com.botwithus.bot.cli.report;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.impl.ReportCommand;
import com.botwithus.bot.core.report.CrashPayload;
import com.botwithus.bot.core.report.LauncherReportChannel;
import com.botwithus.bot.core.report.ProblemKind;
import com.botwithus.bot.core.report.ReportReply;
import com.botwithus.bot.core.report.ReportRequest;
import com.botwithus.bot.core.runlog.CrashPhase;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;
import com.botwithus.bot.core.runlog.ScriptIdentity;
import com.botwithus.bot.core.runlog.ScriptRun;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the host puts in a report, from real run logs on disk and a mocked
 * connection: the crash trimmed, the run it came from, the Store id only when
 * it is a number, the names to hide and the game's pid.
 */
class ScriptReportsTest {

    static final String PIPE = "BotWithUs_14208";
    static final String SCRIPT = "Agility Anachronia";
    private static final HostIdentity HOST = new HostIdentity("2.3.0", 22, "Windows 11 10.0", "Java 25");
    private static final KnownNames NAMES = new KnownNames(List.of("Main Acc"), List.of("Zezima"));
    private static final int MANY_CRUMBS = 80;
    private static final String NOTE = "it froze at the bank chest";

    @ScriptManifest(name = SCRIPT, version = "1.4.2")
    static final class AgilityScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @TempDir
    Path root;

    private RunLogs logs;
    private final List<Connection> connections = new ArrayList<>();
    private Map<String, InstalledSdnScript> ledger = Map.of();

    @BeforeEach
    void setUp() {
        logs = new RunLogs(root.resolve("logs"), HOST, Clock.fixed(Instant.parse("2026-10-06T07:12:23Z"),
                ZoneOffset.UTC));
    }

    ScriptReports reports(LauncherReportChannel channel) {
        Function<String, Optional<InstalledSdnScript>> find = cls -> Optional.ofNullable(ledger.get(cls));
        return new ScriptReports(new ScriptReports.Deps(() -> logs, () -> List.<Connection>copyOf(connections),
                find, HOST, channel));
    }

    private ScriptReports reports() {
        return reports(new LauncherReportChannel(root.resolve("reports")));
    }

    private ScriptRun openRun() {
        return logs.open(new RunLogs.RunRequest(new ScriptIdentity(SCRIPT, "1.4.2", "", "local", null), PIPE, 1,
                null, () -> NAMES, null));
    }

    void connectRunner() {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.getScriptName()).thenReturn(SCRIPT);
        when(runner.getScript()).thenReturn(new AgilityScript());
        when(runner.getManifest()).thenReturn(AgilityScript.class.getAnnotation(ScriptManifest.class));
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.findRunner(SCRIPT)).thenReturn(runner);
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(PIPE);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.knownNames()).thenReturn(NAMES);
        connections.add(conn);
    }

    private static JsonObject json(ReportRequest request) {
        return JsonParser.parseString(request.toJson()).getAsJsonObject();
    }

    @Test
    void request_afterACrash_carriesTheTrimmedCrashAndItsRun() {
        connectRunner();
        ledger = Map.of(AgilityScript.class.getName(), new InstalledSdnScript("123", 4, Instant.EPOCH));
        ScriptRun run = openRun();
        for (int i = 0; i < MANY_CRUMBS; i++) {
            run.crumb("rpc", "call " + i);
        }
        run.crash(CrashPhase.ON_LOOP, 7, new IllegalStateException("npc vanished"));
        run.close();

        ReportRequest request = reports().request(new ReportSubject(PIPE, SCRIPT), ProblemKind.CRASHED,
                "it fell off the log");
        JsonObject o = json(request);

        Path logFile = run.logFile().orElseThrow();
        assertAll(
                () -> assertEquals(SCRIPT, o.get("script_name").getAsString()),
                () -> assertEquals("agility-anachronia", o.get("script_slug").getAsString()),
                () -> assertEquals(123, o.get("script_id").getAsLong()),
                () -> assertEquals("1.4.2", o.get("script_version").getAsString()),
                () -> assertEquals("java", o.get("host").getAsString()),
                () -> assertEquals("2.3.0", o.get("host_version").getAsString()),
                () -> assertEquals(run.runId(), o.get("run_id").getAsString()),
                () -> assertEquals(logFile.getParent().toString(), o.get("run_log_dir").getAsString()),
                () -> assertEquals(14208, o.get("game_pid").getAsLong()),
                () -> assertEquals("Main Acc",
                        o.getAsJsonObject("known_names").getAsJsonArray("accounts").get(0).getAsString()),
                () -> assertEquals("on_loop", o.getAsJsonObject("crash").get("phase").getAsString()),
                () -> assertTrue(o.getAsJsonObject("crash").get("exception").getAsString()
                        .contains("npc vanished")),
                () -> assertEquals(CrashPayload.MAX_BREADCRUMBS,
                        o.getAsJsonObject("crash").getAsJsonArray("breadcrumbs").size()),
                () -> assertEquals("it fell off the log", o.get("user_note").getAsString()));
    }

    @Test
    void request_noCrash_closedRun_readsTheRunIdBackFromTheLogHeader() {
        ScriptRun run = openRun();
        run.close();

        JsonObject o = json(reports().request(new ReportSubject(PIPE, SCRIPT), ProblemKind.STUCK, NOTE));

        assertAll(
                () -> assertEquals(run.runId(), o.get("run_id").getAsString()),
                () -> assertTrue(o.get("crash").isJsonNull()),
                () -> assertFalse(o.has("script_id"), "no runner, so no Store id"),
                () -> assertFalse(o.has("script_version")));
    }

    @Test
    void request_openRun_usesItsRunId() {
        ScriptRun run = openRun();

        JsonObject o = json(reports().request(new ReportSubject(PIPE, SCRIPT), ProblemKind.STUCK, NOTE));

        assertEquals(run.runId(), o.get("run_id").getAsString());
    }

    @Test
    void request_catalogueIdThatIsNotAPositiveNumber_isLeftOut() {
        connectRunner();
        for (String id : List.of("abc", "0", "-4", "")) {
            ledger = Map.of(AgilityScript.class.getName(), new InstalledSdnScript(id, null, Instant.EPOCH));
            ReportRequest r = reports().request(new ReportSubject(PIPE, SCRIPT), ProblemKind.STUCK, NOTE);
            assertEquals(OptionalLong.empty(), r.scriptId(), "catalogue id '" + id + "'");
        }
    }

    @Test
    void request_unknownPipe_hasNoPidAndNoNames() {
        ReportRequest r = reports().request(new ReportSubject("not-a-pipe", SCRIPT), ProblemKind.OTHER, NOTE);
        assertAll(
                () -> assertEquals(OptionalLong.empty(), r.gamePid()),
                () -> assertEquals(KnownNames.NONE, r.knownNames()),
                () -> assertEquals(Optional.empty(), r.runId()));
    }

    @Test
    void knows_onlyWhatTheHostHasSeen() {
        ReportSubject subject = new ReportSubject(PIPE, SCRIPT);
        boolean before = reports().knows(subject);
        openRun();
        assertAll(
                () -> assertFalse(before),
                () -> assertTrue(reports().knows(subject)));
    }

    @Test
    void preview_listsTheNewestLogsAndTheCrashLine() throws IOException {
        ScriptRun run = openRun();
        run.crash(CrashPhase.ON_START, 0, new NullPointerException("npc"));
        run.close();
        Path dir = run.logFile().orElseThrow().getParent();
        Files.writeString(dir.resolve("20200101-000000-aaaaaaaa.log"), "old");
        Files.writeString(dir.resolve("20200102-000000-bbbbbbbb.log"), "old");
        Files.writeString(dir.resolve("20200103-000000-cccccccc.log"), "old");
        Files.writeString(dir.resolve("notes.txt"), "not a log");

        ReportPreview preview = reports().previewNow(new ReportSubject(PIPE, SCRIPT));

        assertAll(
                () -> assertEquals(ReportPreview.LOGS_SENT, preview.logFiles().size()),
                () -> assertEquals(run.logFile().orElseThrow().getFileName().toString(),
                        preview.logFiles().getFirst()),
                () -> assertEquals("20200102-000000-bbbbbbbb.log", preview.logFiles().getLast()),
                () -> assertTrue(preview.crashLine().orElseThrow().contains("NullPointerException")));
    }

    @Test
    void sendNow_launcherAnswers_returnsItsReplyAndLeavesNoFiles() throws Exception {
        Path dir = Files.createDirectories(root.resolve("reports"));
        LauncherReportChannel channel = new LauncherReportChannel(dir, new LauncherReportChannel.Timing(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(10)));
        openRun().close();
        FakeLauncher launcher = FakeLauncher.answerOnce(dir,
                "{\"status\":\"ok\",\"code\":\"BWU-ABC234\",\"user_message\":\"Thanks! Code BWU-ABC234.\"}");

        ReportReply reply = reports(channel).sendNow(new ReportSubject(PIPE, SCRIPT), ProblemKind.STUCK, NOTE);
        launcher.join();

        assertAll(
                () -> assertEquals(new ReportReply.Sent("BWU-ABC234", "Thanks! Code BWU-ABC234."),
                        reply),
                () -> assertEquals(List.of(), list(dir)),
                () -> assertTrue(launcher.request().contains("\"script_name\":\"" + SCRIPT + "\"")));
    }

    /** Runs one {@code report} line against a mocked host and returns what it printed. */
    private String runCommand(ScriptReports with, String line) {
        CliContext ctx = mock(CliContext.class);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        when(ctx.out()).thenReturn(new PrintStream(printed, true, StandardCharsets.UTF_8));
        when(ctx.getActiveConnectionName()).thenReturn(PIPE);
        when(ctx.getConnections()).thenReturn(List.copyOf(connections));
        new ReportCommand(with).execute(CommandParser.parse(line), ctx);
        return printed.toString(StandardCharsets.UTF_8);
    }

    /**
     * The headless path end to end: the {@code report} command finds the script
     * on the active client, sends through the channel with both answers, and
     * prints the launcher's message unchanged.
     */
    @Test
    void reportCommand_printsTheLaunchersMessageUnchanged() throws Exception {
        connectRunner();
        openRun().close();
        Path dir = Files.createDirectories(root.resolve("reports"));
        LauncherReportChannel channel = new LauncherReportChannel(dir, new LauncherReportChannel.Timing(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(10)));
        String message = "You're not signed in to the launcher. Sign in, then try again.";
        FakeLauncher launcher = FakeLauncher.answerOnce(dir,
                "{\"status\":\"error\",\"error\":\"not_signed_in\",\"user_message\":\"" + message + "\"}");

        String out = runCommand(reports(channel),
                "report \"" + SCRIPT + "\" --problem stuck --note it got stuck at the gate");
        launcher.join();

        assertAll(
                () -> assertTrue(out.endsWith(message + System.lineSeparator()), out),
                () -> assertTrue(launcher.request().contains("\"user_note\":\"it got stuck at the gate\""),
                        launcher.request()),
                () -> assertTrue(launcher.request().contains("\"problem\":\"stuck\""), launcher.request()));
    }

    /** Every line that lacks a required part prints the usage and sends nothing, launcher or not. */
    @Test
    void reportCommand_missingOrWrongArguments_printUsage() {
        connectRunner();
        openRun().close();
        String usage = "Usage: " + new ReportCommand(reports()).usage() + System.lineSeparator();
        for (String line : List.of("report",
                "report \"" + SCRIPT + "\" --note it got stuck at the gate",
                "report \"" + SCRIPT + "\" --problem stuck",
                "report \"" + SCRIPT + "\" --problem=frozen --note it got stuck at the gate",
                "report \"" + SCRIPT + "\" --problem stuck --note")) {
            assertEquals(usage, runCommand(reports(), line), line);
        }
    }

    @Test
    void reportCommand_shortNote_saysSoInTheLaunchersWords() {
        connectRunner();
        openRun().close();
        assertEquals(ReportReply.Failed.noteTooShort().userMessage() + System.lineSeparator(),
                runCommand(reports(), "report \"" + SCRIPT + "\" --problem=crashed --note=it broke"));
    }

    @Test
    void reportCommand_noLogs_saysLogsMissing_andSendsNothing() throws IOException {
        connectRunner();
        Path dir = Files.createDirectories(root.resolve("reports"));

        String out = runCommand(reports(new LauncherReportChannel(dir)),
                "report \"" + SCRIPT + "\" --problem other --note nothing happened at all today");

        assertAll(
                () -> assertEquals(ReportReply.Failed.logsMissing().userMessage() + System.lineSeparator(), out),
                () -> assertEquals(List.of(), list(dir), "a request was written"));
    }

    @Test
    void reportCommand_unknownScript_sendsNothing() {
        String out = runCommand(reports(), "report Nope --problem other --note nothing happened at all today");

        assertTrue(out.contains("Nothing is known about a script named"), out);
    }

    /** The host's own checks: no request reaches the launcher's directory. */
    @Test
    void sendNow_withoutLogsOrWithAShortNote_answersHereAndWritesNothing() throws IOException {
        connectRunner();
        Path dir = Files.createDirectories(root.resolve("reports"));
        ScriptReports reports = reports(new LauncherReportChannel(dir));
        ReportSubject subject = new ReportSubject(PIPE, SCRIPT);

        ReportReply noLogs = reports.sendNow(subject, ProblemKind.CRASHED, NOTE);
        openRun().close();
        ReportReply shortNote = reports.sendNow(subject, ProblemKind.CRASHED, "it broke");

        assertAll(
                () -> assertEquals(ReportReply.Failed.logsMissing(), noLogs),
                () -> assertEquals(ReportReply.Failed.noteTooShort(), shortNote),
                () -> assertEquals(List.of(), list(dir)));
    }

    @Test
    void preview_saysWhetherThereAreLogs() {
        ReportSubject subject = new ReportSubject(PIPE, SCRIPT);
        boolean before = reports().previewNow(subject).hasLogs();
        openRun();
        assertAll(
                () -> assertFalse(before),
                () -> assertTrue(reports().previewNow(subject).hasLogs()),
                () -> assertTrue(reports().hasLogs(subject)));
    }

    private static List<String> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }
}
