package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.Test;

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

        AtomicReference<String> errorPhase = new AtomicReference<>();
        runner.setErrorHandler((name, phase, error) -> errorPhase.set(phase));

        runner.start();
        Thread.sleep(200);
        assertEquals("onLoop", errorPhase.get());
    }

    @Test
    void profilerRecordsLoops() throws Exception {
        AtomicBoolean firstLoop = new AtomicBoolean(true);
        BotScript script = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() {
                if (firstLoop.compareAndSet(true, false)) return 10;
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
