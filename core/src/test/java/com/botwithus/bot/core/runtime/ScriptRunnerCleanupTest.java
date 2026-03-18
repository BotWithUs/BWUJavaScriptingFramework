package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that ScriptRunner calls navigation.cleanup() after the script stops.
 */
class ScriptRunnerCleanupTest {

    private static BotScript immediateStopScript() {
        return new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return -1; }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };
    }

    @Test
    void cleanupCalledAfterNormalStop() throws Exception {
        Navigation nav = mock(Navigation.class);
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);

        ScriptRunner runner = new ScriptRunner(immediateStopScript(), ctx);
        runner.start();
        assertTrue(runner.awaitStop(2000), "Script should stop within timeout");

        verify(nav).cleanup();
    }

    @Test
    void cleanupCalledAfterOnLoopError() throws Exception {
        Navigation nav = mock(Navigation.class);
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);

        BotScript errorScript = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { throw new RuntimeException("boom"); }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };

        ScriptRunner runner = new ScriptRunner(errorScript, ctx);
        runner.start();
        assertTrue(runner.awaitStop(2000));

        verify(nav).cleanup();
    }

    @Test
    void cleanupCalledAfterExplicitStop() throws Exception {
        Navigation nav = mock(Navigation.class);
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);

        CountDownLatch loopStarted = new CountDownLatch(1);
        BotScript longRunning = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() {
                loopStarted.countDown();
                return 5000;
            }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };

        ScriptRunner runner = new ScriptRunner(longRunning, ctx);
        runner.start();
        assertTrue(loopStarted.await(2, TimeUnit.SECONDS));

        runner.stop();
        assertTrue(runner.awaitStop(2000));

        verify(nav).cleanup();
    }

    @Test
    void cleanupErrorDoesNotPropagateOrPreventShutdown() throws Exception {
        Navigation nav = mock(Navigation.class);
        doThrow(new RuntimeException("cleanup failed")).when(nav).cleanup();
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);

        ScriptRunner runner = new ScriptRunner(immediateStopScript(), ctx);
        runner.start();
        assertTrue(runner.awaitStop(2000), "Script should stop even if cleanup throws");

        assertFalse(runner.isRunning());
    }

    @Test
    void onStopCalledBeforeCleanup() throws Exception {
        Navigation nav = mock(Navigation.class);
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);

        StringBuilder callOrder = new StringBuilder();
        BotScript orderTracker = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return -1; }
            @Override public void onStop() { callOrder.append("onStop,"); }
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };

        doAnswer(inv -> {
            callOrder.append("cleanup,");
            return null;
        }).when(nav).cleanup();

        ScriptRunner runner = new ScriptRunner(orderTracker, ctx);
        runner.start();
        assertTrue(runner.awaitStop(2000));

        assertEquals("onStop,cleanup,", callOrder.toString());
    }
}
