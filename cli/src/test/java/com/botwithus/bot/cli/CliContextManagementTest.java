package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.management.ManagementFile;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.config.ManagementSettingsStore;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first management load pass after an upgrade is the one that gives the
 * scripts it finds the whole host; a script found by a later pass does not get
 * it. A management script reads each client's settings through its context.
 */
class CliContextManagementTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    @TempDir
    Path dir;

    private String previousScriptsDir;

    @BeforeEach
    void useATemporaryScriptsFolder() {
        previousScriptsDir = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, dir.resolve("scripts").toString());
    }

    @AfterEach
    void restoreTheScriptsFolder() {
        if (previousScriptsDir == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previousScriptsDir);
        }
    }

    @Test
    void theFirstLoadPass_isTheMigration_soAScriptFoundLaterStartsNotApplied() {
        CliContext ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));

        ctx.loadManagementScripts();
        ctx.getManagementTargets().recordLoaded(List.of("Newcomer"));

        assertAll(
                () -> assertTrue(Files.exists(dir.resolve(ManagementFile.FILE_NAME))),
                () -> assertTrue(ctx.getManagementTargets().isNotApplied("Newcomer")));
    }
    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String UUID_C = "00000000111111112222222233333333";
    private static final long START_TIMEOUT_S = 5;

    @Test
    void aManagementScript_getsEachClientsMergedSettings_throughTheContextItIsStartedWith() throws Exception {
        CliContext ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));
        GroupId woodcutters = TestContexts.createGroup(ctx, "Woodcutters");
        ctx.getGroupStore().addMember(woodcutters, ClientKey.account(UUID_A));
        ctx.getGroupStore().addMember(woodcutters, ClientKey.account(UUID_B));
        ctx.initManagementRuntime();
        BreakScheduler script = new BreakScheduler();
        ManagementScriptRunner runner = ctx.getManagementRuntime().registerScript(script);
        ManagementTargets targets = ctx.getManagementTargets();
        Target.ClientScript aWoodcutting = new Target.ClientScript(UUID_A, "Woodcutting");
        targets.add(BreakScheduler.NAME, new Target.Group(woodcutters));
        targets.add(BreakScheduler.NAME, aWoodcutting);
        runner.applyConfig(new ScriptConfig(Map.of("breakEvery", "60", "breakLength", "15")));
        ctx.getManagementSettings().apply(BreakScheduler.NAME, new Target.Group(woodcutters),
                new ScriptConfig(Map.of("breakEvery", "120", "breakLength", "15")), BreakScheduler.FIELDS);
        ctx.getManagementSettings().apply(BreakScheduler.NAME, aWoodcutting,
                new ScriptConfig(Map.of("breakEvery", "120", "breakLength", "5")), BreakScheduler.FIELDS);

        runner.start();
        ManagementContext context = script.started.get(START_TIMEOUT_S, TimeUnit.SECONDS);

        assertAll(
                () -> assertEquals(Map.of("breakEvery", "120", "breakLength", "5"), context.configFor(UUID_A).asMap()),
                () -> assertEquals(Map.of("breakEvery", "120", "breakLength", "15"),
                        context.configFor(UUID_A, "Divination").asMap()),
                () -> assertEquals(Map.of("breakEvery", "120", "breakLength", "15"), context.configFor(UUID_B).asMap()),
                () -> assertEquals(Map.of("breakEvery", "60", "breakLength", "15"), context.configFor(UUID_C).asMap()),
                () -> assertTrue(Files.isDirectory(dir.resolve("config").resolve(ManagementSettingsStore.BUCKET)),
                        "kept beside the groups file, not in the home folder"));
    }

    /** A management script with two settings, handing over the context it is started with. */
    @ScriptManifest(name = BreakScheduler.NAME, version = "1.0", author = "test")
    static final class BreakScheduler implements ManagementScript {

        static final String NAME = "Break Scheduler";
        static final List<ConfigField> FIELDS = List.of(
                ConfigField.intField("breakEvery", "Break every (min)", 90),
                ConfigField.intField("breakLength", "Break length (min)", 15));

        final CompletableFuture<ManagementContext> started = new CompletableFuture<>();

        @Override public void onStart(ManagementContext ctx) { started.complete(ctx); }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
        @Override public List<ConfigField> getConfigFields() { return FIELDS; }
    }
}
