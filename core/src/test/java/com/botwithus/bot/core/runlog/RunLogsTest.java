package com.botwithus.bot.core.runlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogsTest {

    private static final Instant NOW = Instant.parse("2026-10-06T07:12:23.529Z");
    private static final HostIdentity HOST =
            new HostIdentity("1.0-SNAPSHOT", 22, "Windows 11 10.0", "Java 25.0.1");
    private static final KnownNames NAMES = new KnownNames(
            List.of("Main Acc", "dave.smith@example.com"), List.of("Zezima"));

    @TempDir
    Path root;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RunLogs logs() {
        return new RunLogs(root, HOST, clock);
    }

    private static RunLogs.RunRequest request(String scriptName) {
        return new RunLogs.RunRequest(new ScriptIdentity(scriptName, "1.2", "", "local", null),
                "Main Acc", 2, AgentIdentity.UNKNOWN, () -> NAMES,
                f -> f.getClassName().startsWith(RunLogsTest.class.getName()));
    }

    @Test
    void open_createsTheSpecPath_andWritesTheHeader() throws Exception {
        ScriptRun run = logs().open(request("[BWU] Agility!"));
        run.close();
        Path file = run.logFile().orElseThrow();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(root.resolve("bwu-agility"), file.getParent()),
                () -> assertTrue(file.getFileName().toString()
                        .matches("20261006-071223-[0-9a-f]{8}\\.log"), file.getFileName().toString()),
                () -> assertTrue(run.runId().matches("[0-9a-f]{32}")),
                () -> assertTrue(file.getFileName().toString().contains(run.runId().substring(0, 8))),
                () -> assertEquals("# bwu-run-log v1", lines.getFirst()),
                () -> assertEquals("run_id: " + run.runId(), lines.get(1)),
                () -> assertTrue(lines.contains("script_author: unknown")),
                () -> assertTrue(lines.contains("agent_build: unknown")),
                () -> assertTrue(lines.contains("slot: 2")),
                () -> assertTrue(lines.contains("---")));
    }

    @Test
    void crash_writesTheSpecBlock_andTheSummaryForAReport() throws Exception {
        RunLogs logs = logs();
        ScriptRun run = logs.open(request("Crasher"));
        run.crumb("rpc", "interact npc=3021 option=Talk-to by Zezima");
        run.crumb("state", "RUNNING->CRASHED");
        CrashSummary summary = run.crash(CrashPhase.ON_LOOP, 1234, failingFrame());
        run.close();

        List<String> lines = Files.readAllLines(run.logFile().orElseThrow(), StandardCharsets.UTF_8);
        int open = indexStartingWith(lines, "=== CRASH ");
        assertAll(
                () -> assertEquals("=== CRASH phase=on_loop iteration=1234 at=2026-10-06T07:12:23.529Z ===",
                        lines.get(open)),
                () -> assertEquals("exception: java.lang.IllegalStateException: boom for Account#1",
                        lines.get(open + 1)),
                () -> assertTrue(lines.get(open + 2).startsWith("top_frame: " + RunLogsTest.class.getName()
                        + ".failingFrame(RunLogsTest.java:"), lines.get(open + 2)),
                () -> assertEquals("  java.lang.IllegalStateException: boom for Account#1", lines.get(open + 3)),
                () -> assertTrue(lines.get(open + 4).startsWith("  at "), lines.get(open + 4)),
                () -> assertTrue(lines.contains("=== BREADCRUMBS last=2 ===")),
                () -> assertTrue(lines.contains(
                        "2026-10-06T07:12:23.529Z rpc interact npc=3021 option=Talk-to by Player#1")),
                () -> assertEquals("=== END ===", lines.getLast()));

        Optional<CrashSummary> last = logs.lastCrash("Main Acc", "Crasher");
        assertAll(
                () -> assertEquals(Optional.of(summary), last),
                () -> assertEquals(CrashPhase.ON_LOOP, summary.phase()),
                () -> assertEquals(2, summary.breadcrumbs().size()),
                () -> assertTrue(summary.stack().startsWith("  java.lang.IllegalStateException")),
                () -> assertFalse(summary.stack().contains("Main Acc")),
                () -> assertEquals(run.logFile(), summary.logFile()));
    }

    @Test
    void topFrame_isUnknown_whenNoFrameIsTheScripts() {
        RunLogs.RunRequest req = new RunLogs.RunRequest(new ScriptIdentity("S", null, null, null, null),
                "c", 1, null, null, f -> false);
        CrashSummary summary = RunLogs.withoutFiles(HOST, clock).open(req)
                .crash(CrashPhase.OTHER, 0, new IllegalStateException("x"));
        assertEquals("unknown", summary.topFrame());
    }

    @Test
    void currentOrLastLog_followsTheNewestRun_andSurvivesClose() {
        RunLogs logs = logs();
        assertTrue(logs.currentOrLastLog("Main Acc", "S").isEmpty());
        ScriptRun first = logs.open(request("S"));
        assertEquals(first.logFile(), logs.currentOrLastLog("Main Acc", "S"));
        first.close();
        assertEquals(first.logFile(), logs.currentOrLastLog("Main Acc", "S"));
        assertTrue(logs.openRun("Main Acc", "S").isEmpty());
        assertTrue(logs.currentOrLastLog("Other", "S").isEmpty());
    }

    @Test
    void rpcCrumbs_andStdLines_goToTheTaggedThreadsRun_only() throws Exception {
        RunLogs logs = logs();
        ScriptRun run = logs.open(request("S"));
        logs.recordRpc("host_call", Map.of());
        logs.stdLine("stdout", "INFO", "host print");
        logs.enter(run);
        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        try {
            logs.recordRpc("queue_action", Map.of("id", 7));
            logs.stdLine("stdout", "INFO", "script print");
            logs.beginLogEvent();
            logs.stdLine("stdout", "INFO", "console echo of a log event");
            logs.endLogEvent();
            Thread child = Thread.ofPlatform().start(() -> {
                try {
                    logs.recordRpc("from_child", Map.of());
                } catch (Throwable t) {
                    childFailure.set(t);
                }
            });
            child.join();
        } finally {
            logs.exit();
        }
        CrashSummary summary = run.crash(CrashPhase.OTHER, 0, new IllegalStateException());
        run.close();
        String text = Files.readString(run.logFile().orElseThrow());
        assertAll(
                () -> assertEquals(null, childFailure.get()),
                () -> assertEquals(2, summary.breadcrumbs().size(), summary.breadcrumbs().toString()),
                () -> assertTrue(summary.breadcrumbs().get(0).endsWith(" rpc queue_action id=7")),
                () -> assertTrue(summary.breadcrumbs().get(1).endsWith(" rpc from_child")),
                () -> assertTrue(text.contains("stdout: script print")),
                () -> assertFalse(text.contains("host print")),
                () -> assertFalse(text.contains("console echo")));
    }

    @Test
    void anUnwritableRoot_stillGivesARun_withCrashSummaries() throws Exception {
        Path blocker = root.resolve("file-not-dir");
        Files.writeString(blocker, "x");
        RunLogs logs = new RunLogs(blocker, HOST, clock);
        ScriptRun run = logs.open(request("S"));
        run.crash(CrashPhase.ON_START, 0, new IllegalStateException("x"));
        assertAll(
                () -> assertTrue(run.logFile().isEmpty()),
                () -> assertTrue(logs.lastCrash("Main Acc", "S").isPresent()));
    }

    /** A real frame in this class, so the top-frame predicate has something to find. */
    private static IllegalStateException failingFrame() {
        return new IllegalStateException("boom for Main Acc");
    }

    private static int indexStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("no line starts with " + prefix + " in " + lines);
    }
}
