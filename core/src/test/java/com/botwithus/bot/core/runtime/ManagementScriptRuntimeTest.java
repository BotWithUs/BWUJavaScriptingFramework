package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementScriptRuntimeTest {

    private ManagementContext ctx;
    private ManagementScriptRuntime runtime;

    @ScriptManifest(name = "AlphaScript", version = "1.0", author = "test")
    static class AlphaScript implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
    }

    @ScriptManifest(name = "BetaScript", version = "2.0", author = "test")
    static class BetaScript implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
    }

    @BeforeEach
    void setUp() {
        ctx = mock(ManagementContext.class);
        runtime = new ManagementScriptRuntime(ctx);
    }

    @Nested
    class Registration {

        @Test
        void registerScriptAddsRunner() {
            ManagementScriptRunner runner = runtime.registerScript(new AlphaScript());
            assertNotNull(runner);
            assertEquals(1, runtime.getRunners().size());
            assertFalse(runner.isRunning());
        }

        @Test
        void registerMultipleScripts() {
            runtime.registerScript(new AlphaScript());
            runtime.registerScript(new BetaScript());
            assertEquals(2, runtime.getRunners().size());
        }

        @Test
        void getRunnersReturnsDefensiveCopy() {
            runtime.registerScript(new AlphaScript());
            List<ManagementScriptRunner> runners = runtime.getRunners();
            assertEquals(1, runners.size());
            assertThrows(UnsupportedOperationException.class, () -> runners.add(null));
        }
    }

    @Nested
    class FindRunner {

        @Test
        void findsByName() {
            runtime.registerScript(new AlphaScript());
            assertNotNull(runtime.findRunner("AlphaScript"));
        }

        @Test
        void findIsCaseInsensitive() {
            runtime.registerScript(new AlphaScript());
            assertNotNull(runtime.findRunner("alphascript"));
            assertNotNull(runtime.findRunner("ALPHASCRIPT"));
        }

        @Test
        void returnsNullForUnknown() {
            assertNull(runtime.findRunner("NonExistent"));
        }
    }

    @Nested
    class StartStop {

        @Test
        void startScriptRegistersAndStarts() throws Exception {
            runtime.startScript(new AlphaScript());
            assertEquals(1, runtime.getRunners().size());
            assertTrue(runtime.getRunners().get(0).isRunning());

            runtime.stopAll();
            Thread.sleep(100);
        }

        @Test
        void stopScriptByName() throws Exception {
            runtime.startScript(new AlphaScript());
            assertTrue(runtime.stopScript("AlphaScript"));
            Thread.sleep(100);
            assertFalse(runtime.getRunners().get(0).isRunning());
        }

        @Test
        void stopScriptReturnsFalseForUnknown() {
            assertFalse(runtime.stopScript("NonExistent"));
        }

        @Test
        void stopScriptReturnsFalseIfNotRunning() {
            runtime.registerScript(new AlphaScript());
            assertFalse(runtime.stopScript("AlphaScript"));
        }

        @Test
        void stopAllClearsRunners() throws Exception {
            runtime.startScript(new AlphaScript());
            runtime.startScript(new BetaScript());
            assertEquals(2, runtime.getRunners().size());

            runtime.stopAll();
            Thread.sleep(100);
            assertEquals(0, runtime.getRunners().size());
        }
    }

    @Nested
    class RemoveScript {

        @Test
        void removeStoppedScript() {
            runtime.registerScript(new AlphaScript());
            assertTrue(runtime.removeScript("AlphaScript"));
            assertEquals(0, runtime.getRunners().size());
        }

        @Test
        void cannotRemoveRunningScript() throws Exception {
            runtime.startScript(new AlphaScript());
            assertFalse(runtime.removeScript("AlphaScript"));
            assertEquals(1, runtime.getRunners().size());

            runtime.stopAll();
            Thread.sleep(100);
        }

        @Test
        void removeNonExistentReturnsFalse() {
            assertFalse(runtime.removeScript("NonExistent"));
        }
    }

    @Nested
    class StateChangeCallback {

        @Test
        void startScriptFiresCallback() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            runtime.setOnStateChange(callCount::incrementAndGet);

            runtime.startScript(new AlphaScript());
            assertEquals(1, callCount.get());

            runtime.stopAll();
            Thread.sleep(100);
        }

        @Test
        void stopScriptFiresCallback() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            runtime.startScript(new AlphaScript());

            runtime.setOnStateChange(callCount::incrementAndGet);
            runtime.stopScript("AlphaScript");
            assertEquals(1, callCount.get());

            Thread.sleep(100);
        }

        @Test
        void stopAllFiresCallback() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            runtime.startScript(new AlphaScript());

            runtime.setOnStateChange(callCount::incrementAndGet);
            runtime.stopAll();
            assertEquals(1, callCount.get());

            Thread.sleep(100);
        }

        @Test
        void noCallbackDoesNotThrow() {
            assertDoesNotThrow(() -> runtime.startScript(new AlphaScript()));
            runtime.stopAll();
        }

        @Test
        void callbackExceptionSwallowed() throws Exception {
            runtime.setOnStateChange(() -> { throw new RuntimeException("boom"); });
            assertDoesNotThrow(() -> runtime.startScript(new AlphaScript()));

            runtime.stopAll();
            Thread.sleep(100);
        }
    }
}
