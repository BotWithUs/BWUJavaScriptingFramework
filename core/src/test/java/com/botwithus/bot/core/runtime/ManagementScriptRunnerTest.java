package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementScriptRunnerTest {

    private static ManagementScript simpleScript(int loopResult) {
        return new ManagementScript() {
            @Override public void onStart(ManagementContext ctx) {}
            @Override public int onLoop() { return loopResult; }
            @Override public void onStop() {}
        };
    }

    static class UnAnnotatedScript implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
    }

    @ScriptManifest(name = "TestMgmt", version = "1.0", author = "test")
    static class AnnotatedScript implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
    }

    @Nested
    class Lifecycle {

        @Test
        void startAndStop() throws Exception {
            ManagementScript script = simpleScript(50);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            assertTrue(runner.isRunning());

            Thread.sleep(150);
            runner.stop();
            assertTrue(runner.awaitStop(1000));
            assertFalse(runner.isRunning());
        }

        @Test
        void stopViaMinusOne() throws Exception {
            ManagementScript script = simpleScript(-1);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            assertTrue(runner.awaitStop(2000));
            assertFalse(runner.isRunning());
        }

        @Test
        void doubleStartIgnored() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            runner.start(); // should be no-op
            assertTrue(runner.isRunning());

            runner.stop();
            runner.awaitStop(1000);
        }

        @Test
        void awaitStopReturnsTrueWhenNotStarted() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            assertTrue(runner.awaitStop(100));
        }

        @Test
        void onStartReceivesContext() throws Exception {
            AtomicReference<ManagementContext> captured = new AtomicReference<>();
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) { captured.set(ctx); }
                @Override public int onLoop() { return -1; }
                @Override public void onStop() {}
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            runner.awaitStop(2000);
            assertSame(ctx, captured.get());
        }

        @Test
        void onStopCalledAfterLoop() throws Exception {
            AtomicBoolean stopCalled = new AtomicBoolean(false);
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { return -1; }
                @Override public void onStop() { stopCalled.set(true); }
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            runner.awaitStop(2000);
            assertTrue(stopCalled.get());
        }

        @Test
        void loopsMultipleTimes() throws Exception {
            AtomicInteger loopCount = new AtomicInteger(0);
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() {
                    if (loopCount.incrementAndGet() >= 3) return -1;
                    return 10;
                }
                @Override public void onStop() {}
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            runner.awaitStop(2000);
            assertEquals(3, loopCount.get());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void onStartErrorStopsScript() throws Exception {
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {
                    throw new RuntimeException("start failed");
                }
                @Override public int onLoop() { return 100; }
                @Override public void onStop() {}
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            AtomicReference<String> errorPhase = new AtomicReference<>();
            runner.setErrorHandler((name, phase, error) -> errorPhase.set(phase));

            runner.start();
            Thread.sleep(200);
            assertFalse(runner.isRunning());
            assertEquals("onStart", errorPhase.get());
        }

        @Test
        void onLoopErrorCallsHandler() throws Exception {
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { throw new RuntimeException("loop error"); }
                @Override public void onStop() {}
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            AtomicReference<String> errorPhase = new AtomicReference<>();
            runner.setErrorHandler((name, phase, error) -> errorPhase.set(phase));

            runner.start();
            runner.awaitStop(2000);
            assertEquals("onLoop", errorPhase.get());
            assertFalse(runner.isRunning());
        }

        @Test
        void onStopErrorCallsHandler() throws Exception {
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { return -1; }
                @Override public void onStop() { throw new RuntimeException("stop error"); }
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            AtomicReference<String> errorPhase = new AtomicReference<>();
            runner.setErrorHandler((name, phase, error) -> errorPhase.set(phase));

            runner.start();
            runner.awaitStop(2000);
            assertEquals("onStop", errorPhase.get());
        }

        @Test
        void noErrorHandlerDoesNotThrow() throws Exception {
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { throw new RuntimeException("boom"); }
                @Override public void onStop() {}
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            runner.start();
            runner.awaitStop(2000);
            assertFalse(runner.isRunning());
        }
    }

    @Nested
    class Metadata {

        @Test
        void manifestFromAnnotation() {
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(new AnnotatedScript(), ctx);

            assertNotNull(runner.getManifest());
            assertEquals("TestMgmt", runner.getManifest().name());
            assertEquals("1.0", runner.getManifest().version());
        }

        @Test
        void scriptNameFromManifest() {
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(new AnnotatedScript(), ctx);

            assertEquals("TestMgmt", runner.getScriptName());
        }

        @Test
        void scriptNameFallsBackToSimpleNameWhenNoManifest() {
            ManagementContext ctx = mock(ManagementContext.class);
            // Use a named class without @ScriptManifest
            ManagementScript script = new UnAnnotatedScript();
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            assertNull(runner.getManifest());
            assertEquals("UnAnnotatedScript", runner.getScriptName());
        }

        @Test
        void getScriptReturnsInstance() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            assertSame(script, runner.getScript());
        }

        @Test
        void getConfigFieldsDelegates() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            assertEquals(List.of(), runner.getConfigFields());
        }
    }

    @Nested
    class Config {

        @Test
        void applyConfigSetsCurrentConfig() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            assertNull(runner.getCurrentConfig());

            ScriptConfig config = mock(ScriptConfig.class);
            runner.applyConfig(config);
            assertSame(config, runner.getCurrentConfig());
        }

        @Test
        void applyConfigCallsOnConfigUpdate() {
            AtomicReference<ScriptConfig> captured = new AtomicReference<>();
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { return -1; }
                @Override public void onStop() {}
                @Override public void onConfigUpdate(ScriptConfig config) { captured.set(config); }
            };
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx);

            ScriptConfig config = mock(ScriptConfig.class);
            runner.applyConfig(config);
            assertSame(config, captured.get());
        }
    }
}
