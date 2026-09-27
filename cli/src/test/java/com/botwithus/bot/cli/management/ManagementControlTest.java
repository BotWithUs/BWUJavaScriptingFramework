package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting, stopping and restarting management scripts through the real {@link CliContext}. */
class ManagementControlTest {

    private static final String BREAKS = "Break Scheduler";
    private static final int LOOP_MS = 20;
    private static final long AWAIT_SECONDS = 5;
    private static final long AWAIT_MS = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);

    /** Counts its starts, and keeps running until stopped. */
    @ScriptManifest(name = BREAKS, version = "1.0", author = "test")
    public static final class BreakScheduler implements ManagementScript {
        final Semaphore starts = new Semaphore(0);
        @Override public void onStart(ManagementContext ctx) { starts.release(); }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    private static final String WOODCUTTING = "Woodcutting";
    private static final String PIPE = "BotWithUs_1001";
    private static final String UUID = "0123456789abcdef0123456789abcdef";

    @ScriptManifest(name = WOODCUTTING, version = "1.0", author = "test")
    public static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private CliContext ctx;
    private BreakScheduler script;
    private ManagementScriptRunner runner;

    @BeforeEach
    void registerScript() {
        ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));
        ctx.initManagementRuntime();
        script = new BreakScheduler();
        runner = ctx.getManagementRuntime().registerScript(script);
    }

    @AfterEach
    void stopScripts() {
        ctx.getManagementRuntime().stopAll();
    }

    private void awaitStarts(int count) throws InterruptedException {
        assertTrue(script.starts.tryAcquire(count, AWAIT_SECONDS, TimeUnit.SECONDS), "not started");
    }

    @Test
    void startAndStop_rememberWhetherTheScriptShouldRun() throws Exception {
        ManagementControl control = ctx.getManagementControl();

        assertTrue(control.start(BREAKS));
        awaitStarts(1);
        boolean isWantedWhileRunning = ctx.getManagementTargets().isDesiredRunning(BREAKS);
        assertTrue(control.stop(BREAKS));

        assertAll(
                () -> assertTrue(isWantedWhileRunning),
                () -> assertFalse(ctx.getManagementTargets().isDesiredRunning(BREAKS)),
                () -> assertTrue(runner.awaitStop(AWAIT_MS)),
                () -> assertFalse(control.start("Not Loaded")));
    }

    @Test
    void restartIfRunning_startsTheScriptOver() throws Exception {
        ManagementControl control = ctx.getManagementControl();
        control.start(BREAKS);
        awaitStarts(1);

        assertTrue(control.restartIfRunning(BREAKS));

        awaitStarts(1);
        assertTrue(runner.isRunning());
    }

    @Test
    void restartIfRunning_leavesAStoppedScriptStopped() {
        assertAll(
                () -> assertFalse(ctx.getManagementControl().restartIfRunning(BREAKS)),
                () -> assertFalse(runner.isRunning()));
    }

    @Test
    void startWanted_startsWhatShouldBeRunning_asAfterAHostRestart() throws Exception {
        ctx.getManagementTargets().setDesiredRunning(BREAKS, true);

        List<String> started = ctx.getManagementControl().startWanted();

        awaitStarts(1);
        assertEquals(List.of(BREAKS), started);
    }

    @Test
    void stopAllOnGroup_pausesTheManager_andStopsTheGroupsScripts_butKeepsThemLoaded() {
        ScriptRuntime client = TestContexts.runtime(PIPE);
        client.registerScript(new Woodcutter());
        TestContexts.connectIdentified(ctx, PIPE, UUID, "Oakheart", client);
        GroupId woodcutters = TestContexts.createGroup(ctx, "Woodcutters");
        TestContexts.addMember(ctx, "Woodcutters", UUID);
        ctx.getGroupStore().setManager(woodcutters, Optional.of(new ManagerSlot(BREAKS, true)));
        ScriptRunner woodcutting = client.findRunner(WOODCUTTING);
        woodcutting.start();

        ctx.getManagementControl().stopAllOnGroup(woodcutters);

        try {
            assertAll(
                    () -> assertTrue(woodcutting.awaitStop(AWAIT_MS)),
                    () -> assertNotNull(client.findRunner(WOODCUTTING), "still loaded, so it can run again"),
                    () -> assertEquals(Optional.of(new ManagerSlot(BREAKS, false)),
                            ctx.getGroupStore().get(woodcutters).flatMap(ClientGroup::manager)));
        } finally {
            client.stopAll();
        }
    }
}
