package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.runlog.CrashPhase;
import com.botwithus.bot.core.runlog.CrashSummary;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A runner with run logs wired: every lifecycle throw, {@link Error}s included,
 * becomes a crash block in the run's file and a summary a report can read.
 */
class ScriptRunnerRunLogTest {

    private static final long STOP_WAIT_MS = 5_000;
    private static final String CONNECTION = "Main Acc";
    private static final KnownNames NAMES = new KnownNames(List.of(CONNECTION), List.of("Zezima"));

    @TempDir
    Path root;

    private RunLogs logs;

    @BeforeEach
    void setUp() {
        logs = new RunLogs(root, HostIdentity.current(ScriptRunner.class), Clock.systemUTC());
    }

    @ScriptManifest(name = "Npe Script", version = "2.0", author = "Tester")
    static final class NpeOnLoop implements BotScript {
        private String npc;

        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() {
            return npc.length();
        }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    @ScriptManifest(name = "Deep Script")
    static final class OverflowOnLoop implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() {
            return recurse(0);
        }
        private int recurse(int n) {
            return recurse(n + 1) + 1;
        }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    @ScriptManifest(name = "Assert Script")
    static final class ErrorOnStart implements BotScript {
        @Override public void onStart(ScriptContext ctx) {
            throw new AssertionError("Zezima is not ready");
        }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    /** Walks for two loops, then crashes on the third so the crash block carries the ring. */
    @ScriptManifest(name = "Walker Script")
    static final class WalksThenCrashes implements BotScript {
        private final AtomicInteger loops = new AtomicInteger();

        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() {
            if (loops.incrementAndGet() < 3) {
                return 1;
            }
            throw new IllegalStateException("stuck");
        }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    private ScriptRunner runner(BotScript script, ScriptContext ctx) {
        ScriptRunner runner = new ScriptRunner(script, ctx, n -> { }, () -> { }, e -> { });
        runner.setConnectionName(CONNECTION);
        runner.setRunLogging(() -> new RunLogging(logs, () -> NAMES, 4, null));
        return runner;
    }

    private static String runToEnd(ScriptRunner runner, RunLogs logs, String scriptName) throws Exception {
        runner.start();
        assertTrue(runner.awaitStop(STOP_WAIT_MS), "runner did not stop");
        Path file = logs.currentOrLastLog(CONNECTION, scriptName).orElseThrow();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void npeInOnLoop_writesHeaderTraceAndCrashBlock() throws Exception {
        ScriptRunner runner = runner(new NpeOnLoop(), mock(ScriptContext.class));
        String text = runToEnd(runner, logs, "Npe Script");
        CrashSummary crash = logs.lastCrash(CONNECTION, "Npe Script").orElseThrow();
        assertAll(
                () -> assertTrue(text.startsWith("# bwu-run-log v1\n"), text),
                () -> assertTrue(text.contains("\nscript_name: Npe Script\n")),
                () -> assertTrue(text.contains("\nscript_version: 2.0\n")),
                () -> assertTrue(text.contains("\nscript_author: Tester\n")),
                () -> assertTrue(text.contains("\nslot: 4\n")),
                () -> assertTrue(text.contains("=== CRASH phase=on_loop iteration=0 ")),
                () -> assertTrue(text.contains("\nexception: java.lang.NullPointerException")),
                () -> assertTrue(text.contains("\ntop_frame: " + NpeOnLoop.class.getName() + ".onLoop(")),
                () -> assertTrue(text.contains("state RUNNING->CRASHED")),
                () -> assertTrue(text.contains("=== END ===")),
                () -> assertFalse(text.contains(CONNECTION), "connection name redacted"),
                () -> assertEquals(CrashPhase.ON_LOOP, crash.phase()),
                () -> assertEquals(Phase.ON_LOOP, runner.health().lastCrash().orElseThrow().phase()));
    }

    @Test
    void stackOverflowInOnLoop_isACrash_withItsTrace() throws Exception {
        ScriptRunner runner = runner(new OverflowOnLoop(), mock(ScriptContext.class));
        String text = runToEnd(runner, logs, "Deep Script");
        assertAll(
                () -> assertTrue(text.contains("\nexception: java.lang.StackOverflowError")),
                () -> assertTrue(text.contains("\ntop_frame: " + OverflowOnLoop.class.getName() + ".recurse(")),
                () -> assertTrue(text.contains("\n  at " + OverflowOnLoop.class.getName()), "frames"),
                () -> assertEquals(Phase.ON_LOOP, runner.health().lastCrash().orElseThrow().phase()),
                () -> assertEquals(StackOverflowError.class,
                        runner.health().lastCrash().orElseThrow().cause().getClass()));
    }

    @Test
    void anErrorInOnStart_isRecorded_notLostOnTheThread() throws Exception {
        ScriptRunner runner = runner(new ErrorOnStart(), mock(ScriptContext.class));
        String text = runToEnd(runner, logs, "Assert Script");
        assertAll(
                () -> assertEquals(Phase.ON_START, runner.health().lastCrash().orElseThrow().phase()),
                () -> assertTrue(text.contains("=== CRASH phase=on_start ")),
                () -> assertTrue(text.contains("exception: java.lang.AssertionError: Player#1 is not ready")),
                () -> assertTrue(text.contains("state NEW->STARTING")),
                () -> assertTrue(text.contains("state STARTING->CRASHED")));
    }

    @Test
    void theTileCrumb_isRecordedWhenThePlayerMoves() throws Exception {
        GameSnapshot snapshot = mock(GameSnapshot.class);
        when(snapshot.self()).thenReturn(player(3222, 3218), player(3222, 3218), player(3223, 3218));
        GameAPI api = mock(GameAPI.class);
        when(api.snapshot()).thenReturn(snapshot);
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getGameAPI()).thenReturn(api);
        ScriptRunner runner = runner(new WalksThenCrashes(), ctx);
        runToEnd(runner, logs, "Walker Script");
        CrashSummary summary = logs.lastCrash(CONNECTION, "Walker Script").orElseThrow();
        List<String> tiles = summary.breadcrumbs().stream().filter(l -> l.contains(" tile ")).toList();
        assertEquals(2, tiles.size(), summary.breadcrumbs().toString());
        assertTrue(tiles.get(0).endsWith(" tile 3222,3218,0"));
        assertTrue(tiles.get(1).endsWith(" tile 3223,3218,0"));
    }

    private static LocalPlayer player(int x, int y) {
        return new LocalPlayer(0, 100, x, y, 0, 0, -1, -1, 0, -1, 0, true, -1, 99, 99, List.of());
    }
}
