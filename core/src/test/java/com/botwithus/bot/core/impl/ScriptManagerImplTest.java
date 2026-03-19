package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ScriptInfo;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptManagerImplTest {

    private ScriptRuntime runtime;
    private ScriptManagerImpl manager;

    @BeforeEach
    void setUp() {
        ScriptContext ctx = mock(ScriptContext.class);
        runtime = new ScriptRuntime(ctx);
        manager = new ScriptManagerImpl(runtime);
    }

    // ── Test scripts ───────────────────────────────────────────────────────────

    @ScriptManifest(name = "Alpha", version = "1.0", author = "tester",
            description = "Alpha script")
    static class AlphaScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
    }

    @ScriptManifest(name = "Beta", version = "2.0", author = "tester",
            description = "Beta script")
    static class BetaScript implements BotScript {
        volatile String appliedValue;

        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}

        @Override
        public List<ConfigField> getConfigFields() {
            return List.of(ConfigField.stringField("mode", "Mode", "default"));
        }

        @Override
        public void onConfigUpdate(ScriptConfig config) {
            appliedValue = config.getString("mode", "default");
        }
    }

    /** Script that stops immediately (-1 from onLoop). */
    @ScriptManifest(name = "QuickStop", version = "1.0", author = "tester")
    static class QuickStopScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return -1; }
        @Override public void onStop() {}
    }

    // ── listAll / listRunning ──────────────────────────────────────────────────

    @Test
    void listAllEmpty() {
        assertTrue(manager.listAll().isEmpty());
    }

    @Test
    void listAllReturnsRegisteredScripts() {
        runtime.registerScript(new AlphaScript());
        runtime.registerScript(new BetaScript());

        List<ScriptInfo> all = manager.listAll();
        assertEquals(2, all.size());
        assertEquals("Alpha", all.get(0).name());
        assertEquals("Beta", all.get(1).name());
    }

    @Test
    void listRunningFiltersStoppedScripts() {
        runtime.registerScript(new AlphaScript());
        runtime.registerScript(new BetaScript());

        // Nothing started yet
        assertTrue(manager.listRunning().isEmpty());
    }

    @Test
    void listRunningShowsStartedScripts() throws Exception {
        runtime.registerScript(new AlphaScript());
        manager.start("Alpha");
        Thread.sleep(50);

        List<ScriptInfo> running = manager.listRunning();
        assertEquals(1, running.size());
        assertEquals("Alpha", running.get(0).name());
        assertTrue(running.get(0).running());

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    // ── getInfo ────────────────────────────────────────────────────────────────

    @Test
    void getInfoReturnsNullForUnknown() {
        assertNull(manager.getInfo("NonExistent"));
    }

    @Test
    void getInfoReturnsScriptMetadata() {
        runtime.registerScript(new AlphaScript());

        ScriptInfo info = manager.getInfo("Alpha");
        assertNotNull(info);
        assertEquals("Alpha", info.name());
        assertEquals("1.0", info.version());
        assertEquals("tester", info.author());
        assertEquals("Alpha script", info.description());
        assertFalse(info.running());
        assertTrue(info.className().contains("AlphaScript"));
    }

    // ── isRunning ──────────────────────────────────────────────────────────────

    @Test
    void isRunningReturnsFalseForUnknown() {
        assertFalse(manager.isRunning("NonExistent"));
    }

    @Test
    void isRunningReturnsFalseForStopped() {
        runtime.registerScript(new AlphaScript());
        assertFalse(manager.isRunning("Alpha"));
    }

    @Test
    void isRunningReturnsTrueAfterStart() throws Exception {
        runtime.registerScript(new AlphaScript());
        manager.start("Alpha");
        Thread.sleep(50);

        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    // ── start ──────────────────────────────────────────────────────────────────

    @Test
    void startReturnsFalseForUnknown() {
        assertFalse(manager.start("NonExistent"));
    }

    @Test
    void startLaunchesScript() throws Exception {
        runtime.registerScript(new AlphaScript());

        assertTrue(manager.start("Alpha"));
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    @Test
    void startThrowsIfAlreadyRunning() throws Exception {
        runtime.registerScript(new AlphaScript());
        manager.start("Alpha");
        Thread.sleep(50);

        assertThrows(IllegalStateException.class, () -> manager.start("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    // ── start with config ──────────────────────────────────────────────────────

    @Test
    void startWithConfigAppliesValues() throws Exception {
        BetaScript beta = new BetaScript();
        runtime.registerScript(beta);

        manager.start("Beta", Map.of("mode", "aggressive"));

        // applyConfig calls onConfigUpdate synchronously before start,
        // so the value should be set immediately
        assertEquals("aggressive", beta.appliedValue);

        Thread.sleep(50);
        manager.stop("Beta");
        Thread.sleep(100);
    }

    @Test
    void startWithEmptyConfigStillStarts() throws Exception {
        runtime.registerScript(new AlphaScript());

        assertTrue(manager.start("Alpha", Map.of()));
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    @Test
    void startWithNullConfigStillStarts() throws Exception {
        runtime.registerScript(new AlphaScript());

        assertTrue(manager.start("Alpha", null));
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    // ── stop ───────────────────────────────────────────────────────────────────

    @Test
    void stopReturnsFalseForUnknown() {
        assertFalse(manager.stop("NonExistent"));
    }

    @Test
    void stopStopsRunningScript() throws Exception {
        runtime.registerScript(new AlphaScript());
        manager.start("Alpha");
        Thread.sleep(50);

        assertTrue(manager.stop("Alpha"));
        Thread.sleep(100);
        assertFalse(manager.isRunning("Alpha"));
    }

    // ── restart ────────────────────────────────────────────────────────────────

    @Test
    void restartReturnsFalseForUnknown() {
        assertFalse(manager.restart("NonExistent"));
    }

    @Test
    void restartStoppedScript() throws Exception {
        runtime.registerScript(new AlphaScript());

        assertTrue(manager.restart("Alpha"));
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    @Test
    void restartRunningScript() throws Exception {
        runtime.registerScript(new AlphaScript());
        manager.start("Alpha");
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        assertTrue(manager.restart("Alpha"));
        Thread.sleep(50);
        assertTrue(manager.isRunning("Alpha"));

        manager.stop("Alpha");
        Thread.sleep(100);
    }

    // ── stopAll ────────────────────────────────────────────────────────────────

    @Test
    void stopAllStopsEverything() throws Exception {
        runtime.registerScript(new AlphaScript());
        runtime.registerScript(new BetaScript());
        manager.start("Alpha");
        manager.start("Beta");
        Thread.sleep(50);

        assertEquals(2, manager.listRunning().size());

        manager.stopAll();
        Thread.sleep(100);
        assertTrue(manager.listRunning().isEmpty());
    }

    // ── ScriptInfo record ──────────────────────────────────────────────────────

    @Test
    void scriptInfoRecordFields() {
        ScriptInfo info = new ScriptInfo("Test", "1.0", "me", "desc", ScriptCategory.COMBAT, true, "com.Test");
        assertEquals("Test", info.name());
        assertEquals("1.0", info.version());
        assertEquals("me", info.author());
        assertEquals("desc", info.description());
        assertEquals(ScriptCategory.COMBAT, info.category());
        assertTrue(info.running());
        assertEquals("com.Test", info.className());
    }

    // ── shutdown ───────────────────────────────────────────────────────────────

    @Test
    void shutdownIsIdempotent() {
        manager.shutdown();
        manager.shutdown(); // should not throw
    }
}
