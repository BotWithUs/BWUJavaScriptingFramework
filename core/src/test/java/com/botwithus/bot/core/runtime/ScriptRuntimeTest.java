package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ScriptManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptRuntimeTest {

    @ScriptManifest(name = "TestScript", version = "1.0", author = "test")
    static class TestScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    @Test
    void registerScript() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);

        ScriptRunner runner = runtime.registerScript(new TestScript());
        assertNotNull(runner);
        assertEquals(1, runtime.getRunners().size());
        assertFalse(runner.isRunning());
    }

    @Test
    void findRunner() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.registerScript(new TestScript());

        assertNotNull(runtime.findRunner("TestScript"));
        assertNull(runtime.findRunner("NonExistent"));
    }

    @Test
    void startAndStopScript() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.startScript(new TestScript());

        assertEquals(1, runtime.getRunners().size());
        assertTrue(runtime.getRunners().get(0).isRunning());

        runtime.stopAll();
        Thread.sleep(100);
        assertEquals(0, runtime.getRunners().size());
    }

    @Test
    void removeScript() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.registerScript(new TestScript());

        assertTrue(runtime.removeScript("TestScript"));
        assertEquals(0, runtime.getRunners().size());
    }
}
