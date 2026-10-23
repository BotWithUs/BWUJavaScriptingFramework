package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Management scripts that were running when the host stopped start again once
 * the first load pass has registered them, and only then: a later pass leaves
 * what runs to the reload.
 */
class ManagementStartupTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";
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

    @TempDir
    Path dir;

    private String previousScriptsDir;
    private CliContext ctx;
    private BreakScheduler script;
    private ManagementScriptRunner runner;

    @BeforeEach
    void registerAWantedScript() {
        previousScriptsDir = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, dir.resolve("scripts").toString());
        ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));
        ctx.initManagementRuntime();
        script = new BreakScheduler();
        runner = ctx.getManagementRuntime().registerScript(script);
        ctx.getManagementTargets().setDesiredRunning(BREAKS, true);
    }

    @AfterEach
    void stopScripts() {
        ctx.getManagementRuntime().stopAll();
        if (previousScriptsDir == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previousScriptsDir);
        }
    }

    @Test
    void theFirstPass_startsWhatShouldRun_andALaterPassStartsNothing() throws Exception {
        ManagementStartup startup = new ManagementStartup(ctx.getManagementControl());
        boolean wasRunningBefore = runner.isRunning();

        List<String> first = startup.afterLoadPass();
        assertTrue(script.starts.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS), "not started");
        runner.stop();
        assertTrue(runner.awaitStop(AWAIT_MS));
        List<String> second = startup.afterLoadPass();

        assertAll(
                () -> assertFalse(wasRunningBefore, "nothing starts before a load pass"),
                () -> assertEquals(List.of(BREAKS), first),
                () -> assertEquals(List.of(), second),
                () -> assertFalse(runner.isRunning(), "a later pass does not start it again"),
                () -> assertTrue(ctx.getManagementTargets().isDesiredRunning(BREAKS),
                        "stopped by the runner, not the user: it is still wanted"));
    }

    @Test
    void theHostsLoadPass_isTheFirstPass() {
        boolean isLoadedBefore = ctx.hasLoadedManagement();

        ctx.reloadManagementScripts(AfterReload.REGISTER_ONLY);

        assertAll(
                () -> assertFalse(isLoadedBefore),
                () -> assertTrue(ctx.hasLoadedManagement(), "the reload's pass counts as the first"));
    }
}
