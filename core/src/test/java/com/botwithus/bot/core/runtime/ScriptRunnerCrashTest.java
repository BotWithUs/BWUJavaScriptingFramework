package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ScriptRunnerCrashTest {

    private static BotScript throwingOnLoop(String message) {
        return new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { throw new IllegalStateException(message); }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };
    }

    private static BotScript throwingOnStart(String message) {
        return new BotScript() {
            @Override public void onStart(ScriptContext ctx) {
                throw new IllegalStateException(message);
            }
            @Override public int onLoop() { return -1; }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };
    }

    @Test
    void healthCapturedOnLoopCrash() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        ScriptRunner runner = new ScriptRunner(throwingOnLoop("boom"), ctx,
                ConnectionContext::set, ConnectionContext::clear, events::add);

        runner.start();
        Thread.sleep(250);

        ScriptHealth health = runner.health();
        assertTrue(health.lastCrash().isPresent());
        assertEquals(Phase.ON_LOOP, health.lastCrash().get().phase());
        assertEquals(1L, health.totalCrashes());
        assertEquals("boom", health.lastCrash().get().cause().getMessage());
    }

    @Test
    void scriptCrashedEventPublished() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        ScriptRunner runner = new ScriptRunner(throwingOnLoop("crash-on-loop"), ctx,
                ConnectionContext::set, ConnectionContext::clear, events::add);

        runner.start();
        Thread.sleep(250);

        ScriptCrashedEvent event = events.stream()
                .filter(ScriptCrashedEvent.class::isInstance)
                .map(ScriptCrashedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ScriptCrashedEvent published"));
        assertEquals(Phase.ON_LOOP, event.crash().phase());
        assertEquals("crash-on-loop", event.crash().cause().getMessage());
    }

    @Test
    void onStartCrashCapturedAsStartPhase() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        ScriptRunner runner = new ScriptRunner(throwingOnStart("start-boom"), ctx,
                ConnectionContext::set, ConnectionContext::clear, events::add);

        runner.start();
        Thread.sleep(200);

        ScriptHealth health = runner.health();
        assertTrue(health.lastCrash().isPresent());
        assertEquals(Phase.ON_START, health.lastCrash().get().phase());
        assertFalse(runner.isRunning());
    }

    @Test
    void healthyByDefault() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(throwingOnLoop("never-runs"), ctx);
        assertEquals(ScriptHealth.HEALTHY, runner.health());
        assertEquals(0L, runner.health().totalCrashes());
        assertTrue(runner.health().lastCrash().isEmpty());
    }

    @Test
    void crashHandlerExceptionsDoNotPropagate() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(throwingOnLoop("boom"), ctx);
        runner.setErrorHandler(crash -> { throw new RuntimeException("handler-exploded"); });

        runner.start();
        Thread.sleep(250);
        // Surviving without throwing is the assertion — runner should still
        // record the crash in health() and not propagate the handler error.
        assertTrue(runner.health().lastCrash().isPresent());
    }
}
