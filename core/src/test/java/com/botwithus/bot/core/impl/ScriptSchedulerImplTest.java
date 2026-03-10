package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptSchedulerImplTest {

    private ScriptRuntime runtime;
    private ScriptManagerImpl manager;
    private ScriptSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        ScriptContext ctx = mock(ScriptContext.class);
        runtime = new ScriptRuntime(ctx);
        manager = new ScriptManagerImpl(runtime);
        scheduler = (ScriptSchedulerImpl) manager.getScheduler();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        runtime.stopAll();
    }

    // ── Test scripts ───────────────────────────────────────────────────────────

    @ScriptManifest(name = "Scheduled", version = "1.0", author = "test")
    static class ScheduledScript implements BotScript {
        static final AtomicInteger startCount = new AtomicInteger(0);

        @Override public void onStart(ScriptContext ctx) { startCount.incrementAndGet(); }
        @Override public int onLoop() { return 50; }
        @Override public void onStop() {}
    }

    @ScriptManifest(name = "Counter", version = "1.0", author = "test")
    static class CounterScript implements BotScript {
        static final AtomicInteger startCount = new AtomicInteger(0);

        @Override public void onStart(ScriptContext ctx) { startCount.incrementAndGet(); }
        @Override public int onLoop() { return 50; }
        @Override public void onStop() {}
    }

    // ── runAfter ───────────────────────────────────────────────────────────────

    @Test
    void runAfterSchedulesDelayedStart() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        String id = scheduler.runAfter("Scheduled", Duration.ofMillis(200));
        assertNotNull(id);
        assertFalse(id.isEmpty());

        // Not started yet
        assertFalse(manager.isRunning("Scheduled"));

        // Wait for it
        Thread.sleep(400);
        assertTrue(manager.isRunning("Scheduled"));
        assertEquals(1, ScheduledScript.startCount.get());

        manager.stop("Scheduled");
    }

    @Test
    void runAfterAppearsInListScheduled() {
        runtime.registerScript(new ScheduledScript());

        String id = scheduler.runAfter("Scheduled", Duration.ofSeconds(60));

        List<ScriptScheduler.ScheduledEntry> entries = scheduler.listScheduled();
        assertEquals(1, entries.size());
        assertEquals(id, entries.get(0).id());
        assertEquals("Scheduled", entries.get(0).scriptName());
        assertNull(entries.get(0).interval()); // one-shot
        assertNull(entries.get(0).maxDuration());

        scheduler.cancel(id);
    }

    @Test
    void runAfterRemovedFromListAfterFiring() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        scheduler.runAfter("Scheduled", Duration.ofMillis(100));
        assertEquals(1, scheduler.listScheduled().size());

        Thread.sleep(300);
        assertTrue(scheduler.listScheduled().isEmpty());

        manager.stop("Scheduled");
    }

    // ── runAt ──────────────────────────────────────────────────────────────────

    @Test
    void runAtSchedulesAtInstant() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        Instant at = Instant.now().plusMillis(200);
        String id = scheduler.runAt("Scheduled", at);
        assertNotNull(id);

        Thread.sleep(400);
        assertTrue(manager.isRunning("Scheduled"));

        manager.stop("Scheduled");
    }

    @Test
    void runAtInThePastStartsImmediately() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        String id = scheduler.runAt("Scheduled", Instant.now().minusSeconds(10));
        Thread.sleep(200);

        assertTrue(manager.isRunning("Scheduled"));

        manager.stop("Scheduled");
    }

    // ── runEvery ───────────────────────────────────────────────────────────────

    @Test
    void runEveryRepeatsOnInterval() throws Exception {
        runtime.registerScript(new CounterScript());
        CounterScript.startCount.set(0);

        String id = scheduler.runEvery("Counter", Duration.ofMillis(200));
        assertNotNull(id);

        // Wait for at least 2 firings
        Thread.sleep(500);
        assertTrue(CounterScript.startCount.get() >= 2,
                "Expected at least 2 starts, got " + CounterScript.startCount.get());

        // Verify it's in the list as repeating
        var entries = scheduler.listScheduled();
        assertEquals(1, entries.size());
        assertEquals(Duration.ofMillis(200), entries.get(0).interval());
        assertNull(entries.get(0).maxDuration());

        scheduler.cancel(id);
        manager.stop("Counter");
    }

    @Test
    void runEveryWithMaxDurationStopsScript() throws Exception {
        runtime.registerScript(new CounterScript());
        CounterScript.startCount.set(0);

        String id = scheduler.runEvery("Counter",
                Duration.ofMillis(500), Duration.ofMillis(100));

        // First firing should happen at 500ms, then auto-stop after 100ms
        Thread.sleep(750);

        // Should have started at least once
        assertTrue(CounterScript.startCount.get() >= 1);

        // After auto-stop duration, script should be stopped
        Thread.sleep(200);
        // The auto-stop should have kicked in
        assertFalse(manager.isRunning("Counter"),
                "Script should have been auto-stopped after maxDuration");

        var entries = scheduler.listScheduled();
        assertEquals(1, entries.size());
        assertEquals(Duration.ofMillis(100), entries.get(0).maxDuration());

        scheduler.cancel(id);
    }

    // ── cancel ─────────────────────────────────────────────────────────────────

    @Test
    void cancelRemovesSchedule() {
        runtime.registerScript(new ScheduledScript());

        String id = scheduler.runAfter("Scheduled", Duration.ofSeconds(60));
        assertEquals(1, scheduler.listScheduled().size());

        assertTrue(scheduler.cancel(id));
        assertTrue(scheduler.listScheduled().isEmpty());
    }

    @Test
    void cancelReturnsFalseForUnknownId() {
        assertFalse(scheduler.cancel("nonexistent-id"));
    }

    @Test
    void cancelPreventsExecution() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        String id = scheduler.runAfter("Scheduled", Duration.ofMillis(200));
        scheduler.cancel(id);

        Thread.sleep(400);
        assertFalse(manager.isRunning("Scheduled"));
        assertEquals(0, ScheduledScript.startCount.get());
    }

    @Test
    void cancelStopsRepeating() throws Exception {
        runtime.registerScript(new CounterScript());
        CounterScript.startCount.set(0);

        String id = scheduler.runEvery("Counter", Duration.ofMillis(100));
        Thread.sleep(250);
        int countAtCancel = CounterScript.startCount.get();

        scheduler.cancel(id);
        manager.stop("Counter");
        Thread.sleep(300);

        // Should not have increased much after cancel
        assertTrue(CounterScript.startCount.get() <= countAtCancel + 1,
                "Script should not keep starting after cancel");
    }

    // ── cancelAll ──────────────────────────────────────────────────────────────

    @Test
    void cancelAllClearsEverything() {
        runtime.registerScript(new ScheduledScript());
        runtime.registerScript(new CounterScript());

        scheduler.runAfter("Scheduled", Duration.ofSeconds(60));
        scheduler.runEvery("Counter", Duration.ofSeconds(60));
        assertEquals(2, scheduler.listScheduled().size());

        scheduler.cancelAll();
        assertTrue(scheduler.listScheduled().isEmpty());
    }

    // ── listScheduled ──────────────────────────────────────────────────────────

    @Test
    void listScheduledReturnsSnapshot() {
        runtime.registerScript(new ScheduledScript());
        runtime.registerScript(new CounterScript());

        scheduler.runAfter("Scheduled", Duration.ofSeconds(30));
        scheduler.runEvery("Counter", Duration.ofMinutes(5));

        List<ScriptScheduler.ScheduledEntry> entries = scheduler.listScheduled();
        assertEquals(2, entries.size());

        // Verify entries contain correct data
        boolean foundScheduled = entries.stream()
                .anyMatch(e -> "Scheduled".equals(e.scriptName()) && e.interval() == null);
        boolean foundCounter = entries.stream()
                .anyMatch(e -> "Counter".equals(e.scriptName()) && e.interval() != null);
        assertTrue(foundScheduled);
        assertTrue(foundCounter);

        scheduler.cancelAll();
    }

    @Test
    void listScheduledNextRunIsInFuture() {
        runtime.registerScript(new ScheduledScript());

        scheduler.runAfter("Scheduled", Duration.ofMinutes(10));

        var entries = scheduler.listScheduled();
        Instant nextRun = entries.get(0).nextRun();
        assertTrue(nextRun.isAfter(Instant.now()),
                "nextRun should be in the future");

        scheduler.cancelAll();
    }

    // ── shutdown ───────────────────────────────────────────────────────────────

    @Test
    void shutdownCancelsAllAndStopsExecutor() throws Exception {
        runtime.registerScript(new ScheduledScript());
        ScheduledScript.startCount.set(0);

        scheduler.runAfter("Scheduled", Duration.ofMillis(200));
        scheduler.shutdown();

        Thread.sleep(400);
        assertFalse(manager.isRunning("Scheduled"));
        assertEquals(0, ScheduledScript.startCount.get());
        assertTrue(scheduler.listScheduled().isEmpty());
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    @Test
    void multipleSchedulesForSameScript() {
        runtime.registerScript(new ScheduledScript());

        String id1 = scheduler.runAfter("Scheduled", Duration.ofSeconds(30));
        String id2 = scheduler.runAfter("Scheduled", Duration.ofSeconds(60));

        assertNotEquals(id1, id2);
        assertEquals(2, scheduler.listScheduled().size());

        scheduler.cancelAll();
    }

    @Test
    void cancelOneOfMultipleSchedules() {
        runtime.registerScript(new ScheduledScript());

        String id1 = scheduler.runAfter("Scheduled", Duration.ofSeconds(30));
        String id2 = scheduler.runAfter("Scheduled", Duration.ofSeconds(60));

        scheduler.cancel(id1);
        assertEquals(1, scheduler.listScheduled().size());
        assertEquals(id2, scheduler.listScheduled().get(0).id());

        scheduler.cancelAll();
    }
}
