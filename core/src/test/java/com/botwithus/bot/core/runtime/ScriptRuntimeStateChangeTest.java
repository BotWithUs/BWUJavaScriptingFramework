package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptRuntimeStateChangeTest {

    @ScriptManifest(name = "CallbackTestScript", version = "1.0", author = "test")
    static class CallbackTestScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    private ScriptRuntime runtime;
    private AtomicInteger callbackCount;

    @BeforeEach
    void setUp() {
        ScriptContext ctx = mock(ScriptContext.class);
        runtime = new ScriptRuntime(ctx);
        callbackCount = new AtomicInteger(0);
        runtime.setOnStateChange(callbackCount::incrementAndGet);
    }

    @Test
    void startScript_firesCallback() {
        runtime.startScript(new CallbackTestScript());
        assertEquals(1, callbackCount.get());
        runtime.stopAll();
    }

    @Test
    void stopAll_firesCallback() {
        runtime.startScript(new CallbackTestScript());
        callbackCount.set(0); // reset after start

        runtime.stopAll();
        assertEquals(1, callbackCount.get());
    }

    @Test
    void stopScript_firesCallback() {
        runtime.startScript(new CallbackTestScript());
        callbackCount.set(0);

        runtime.stopScript("CallbackTestScript");
        assertEquals(1, callbackCount.get());
    }

    @Test
    void stopScript_doesNotFireForNonExistent() {
        runtime.stopScript("NonExistentScript");
        assertEquals(0, callbackCount.get());
    }

    @Test
    void noCallback_doesNotThrow() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime rt = new ScriptRuntime(ctx);
        // No callback set — should not throw
        assertDoesNotThrow(() -> rt.startScript(new CallbackTestScript()));
        rt.stopAll();
    }

    @Test
    void multipleStarts_fireMultipleCallbacks() {
        runtime.startScript(new CallbackTestScript());
        runtime.startScript(new CallbackTestScript());
        assertEquals(2, callbackCount.get());
        runtime.stopAll();
    }

    @Test
    void callbackExceptionDoesNotCrashRuntime() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime rt = new ScriptRuntime(ctx);
        rt.setOnStateChange(() -> { throw new RuntimeException("boom"); });

        // Should not throw
        assertDoesNotThrow(() -> rt.startScript(new CallbackTestScript()));
        rt.stopAll();
    }
}
