package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.runtime.Phase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptRunnerTest {

    private static BotScript simpleScript(int loopResult) {
        return new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return loopResult; }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };
    }

    @Test
    void startAndStop() throws Exception {
        BotScript script = simpleScript(100);
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);

        runner.start();
        assertTrue(runner.isRunning());

        Thread.sleep(250);
        runner.stop();
        Thread.sleep(100);
        assertFalse(runner.isRunning());
    }

    @Test
    void lastStartedAt_isNullBeforeStartAndMovesOnRestart() throws Exception {
        ScriptRunner runner = new ScriptRunner(simpleScript(-1), mock(ScriptContext.class));
        assertNull(runner.lastStartedAt(), "never started");

        Instant before = Instant.now();
        runner.start();
        Instant first = runner.lastStartedAt();
        assertNotNull(first);
        assertFalse(first.isBefore(before), "stamped at start, not earlier");

        assertTrue(runner.awaitStop(2_000), "onLoop returned -1, so the run ends");
        Thread.sleep(5);
        runner.start();
        assertTrue(runner.lastStartedAt().isAfter(first), "a restart restamps");
        runner.stop();
    }

    @Test
    void lastStartedAt_precedesTheCrashOfThatRun() throws Exception {
        BotScript script = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { throw new IllegalStateException("boom"); }
            @Override public void onStop() {}
        };
        ScriptRunner runner = new ScriptRunner(script, mock(ScriptContext.class));
        runner.start();
        assertTrue(runner.awaitStop(2_000));

        Instant crashed = runner.health().lastCrash().orElseThrow().when();
        assertFalse(crashed.isBefore(runner.lastStartedAt()), "this run's crash is not older than its start");
    }

    @Test
    void stopViaMinusOne() throws Exception {
        BotScript script = simpleScript(-1);
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);

        runner.start();
        Thread.sleep(200);
        assertFalse(runner.isRunning());
    }

    @Test
    void errorHandlerCalled() throws Exception {
        BotScript script = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { throw new RuntimeException("test error"); }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };

        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);

        AtomicReference<Phase> errorPhase = new AtomicReference<>();
        runner.setErrorHandler(crash -> errorPhase.set(crash.phase()));

        runner.start();
        Thread.sleep(200);
        assertEquals(Phase.ON_LOOP, errorPhase.get());
        assertTrue(runner.health().lastCrash().isPresent());
        assertEquals(Phase.ON_LOOP, runner.health().lastCrash().get().phase());
        assertEquals(1L, runner.health().totalCrashes());
    }

    @Test
    void profilerRecordsLoops() throws Exception {
        AtomicBoolean firstLoop = new AtomicBoolean(true);
        BotScript script = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() {
                if (firstLoop.compareAndSet(true, false)) {
                    return 10;
                }
                return -1; // stop after 2nd loop
            }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };

        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);
        runner.start();
        Thread.sleep(300);

        assertTrue(runner.getProfiler().getLoopCount() >= 1);
    }
}
