package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.config.ManagementSettingsStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
                    if (loopCount.incrementAndGet() >= 3) {
                        return -1;
                    }
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

    /**
     * A management script whose {@code onStart} throws never runs, but its run
     * still has to end: the stop latch released and the failure reported once.
     */
    @Nested
    class StartFailure {

        private static final String CAUSE = "no orchestrator";
        private static final long WAIT_MS = 5000L;

        @ScriptManifest(name = "BadStartMgmt", version = "1.0", author = "test")
        private static final class BadStart implements ManagementScript {
            private final AtomicInteger onStopCalls = new AtomicInteger();

            @Override public void onStart(ManagementContext ctx) {
                throw new IllegalStateException(CAUSE);
            }

            @Override public int onLoop() {
                return -1;
            }

            @Override public void onStop() {
                onStopCalls.incrementAndGet();
            }
        }

        @Test
        void awaitStop_afterOnStartThrows_returnsTrue() {
            ManagementScriptRunner runner =
                    new ManagementScriptRunner(new BadStart(), mock(ManagementContext.class));

            runner.start();

            assertTrue(runner.awaitStop(WAIT_MS),
                    "a run that failed to start never released its stop latch");
        }

        @Test
        void startFailure_isRecordedAsExactlyOneCrash() {
            BadStart script = new BadStart();
            ManagementScriptRunner runner =
                    new ManagementScriptRunner(script, mock(ManagementContext.class));
            List<String> handledPhases = new CopyOnWriteArrayList<>();
            List<LastCrash> heard = new CopyOnWriteArrayList<>();
            runner.setErrorHandler((name, phase, error) -> handledPhases.add(phase));
            runner.setRunnerListener(new RunnerListener() {
                @Override public void managementScriptCrashed(String scriptName, LastCrash crash) {
                    heard.add(crash);
                }
            });

            runner.start();
            assertTrue(runner.awaitStop(WAIT_MS));

            assertAll(
                    () -> assertFalse(runner.isRunning()),
                    () -> assertEquals(1L, runner.health().totalCrashes()),
                    () -> assertEquals(Phase.ON_START,
                            runner.health().lastCrash().orElseThrow().phase()),
                    () -> assertEquals(List.of("onStart"), handledPhases),
                    () -> assertEquals(1, heard.size(), "listener crash reports"),
                    () -> assertEquals(CAUSE, heard.getFirst().cause().getMessage()),
                    () -> assertEquals(0, script.onStopCalls.get(),
                            "a script that never started is not stopped"));
        }

        @Test
        void stopAll_afterStartFailure_doesNotQuarantineTheRunner() {
            ManagementScriptRuntime runtime =
                    new ManagementScriptRuntime(mock(ManagementContext.class));
            ManagementScriptRunner runner = runtime.registerScript(new BadStart());
            runner.start();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (runner.isThreadAlive()) {
                if (System.nanoTime() > deadline) {
                    fail("script thread never exited");
                }
                Thread.onSpinWait();
            }

            runtime.stopAll();

            assertEquals(List.of(), runtime.getRunners(),
                    "a runner whose thread has exited is not a zombie");
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

        private static final long AWAIT_MS = 2000;

        @TempDir
        Path configDir;

        /** A store over a temporary folder, saving in line, so nothing reaches the real home folder. */
        private ManagementSettingsStore store() {
            return new ManagementSettingsStore(configDir, Runnable::run);
        }

        @Test
        void applyConfigSetsCurrentConfig() {
            ManagementScript script = simpleScript(100);
            ManagementContext ctx = mock(ManagementContext.class);
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx, store());

            assertNull(runner.getCurrentConfig());

            ScriptConfig config = new ScriptConfig(Map.of("delay", "5"));
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
            ManagementScriptRunner runner = new ManagementScriptRunner(script, ctx, store());

            ScriptConfig config = new ScriptConfig(Map.of("delay", "5"));
            runner.applyConfig(config);
            assertSame(config, captured.get());
        }

        @Test
        void appliedConfig_isSavedAsTheScriptsDefaults() {
            ManagementSettingsStore store = store();
            ManagementScriptRunner runner = new ManagementScriptRunner(new AnnotatedScript(),
                    mock(ManagementContext.class), store);

            runner.applyConfig(new ScriptConfig(Map.of("delay", "5")));

            assertEquals(Map.of("delay", "5"), store().savedDefaults("TestMgmt"));
        }

        @Test
        void onStart_theScriptIsHandedTheSavedDefaults() throws InterruptedException {
            store().saveDefaults("TestMgmt", Map.of("delay", "5"));
            CountDownLatch updated = new CountDownLatch(1);
            AtomicReference<ScriptConfig> captured = new AtomicReference<>();
            ManagementScript script = new ConfiguredScript(config -> {
                captured.set(config);
                updated.countDown();
            });
            ManagementScriptRunner runner = new ManagementScriptRunner(script, mock(ManagementContext.class), store());

            runner.start();

            assertTrue(updated.await(AWAIT_MS, TimeUnit.MILLISECONDS), "onConfigUpdate was called");
            assertEquals(Map.of("delay", "5", "relog", "true"), captured.get().asMap());
        }
    }

    /** A named script with two fields, telling {@code onUpdate} about each config it is given. */
    @ScriptManifest(name = "TestMgmt", version = "1.0", author = "test")
    static final class ConfiguredScript implements ManagementScript {

        private final Consumer<ScriptConfig> onUpdate;

        ConfiguredScript(Consumer<ScriptConfig> onUpdate) {
            this.onUpdate = onUpdate;
        }

        @Override public void onStart(ManagementContext ctx) {}
        @Override public int onLoop() { return -1; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() {
            return List.of(ConfigField.intField("delay", "Delay", 30), ConfigField.boolField("relog", "Relog", true));
        }
        @Override public void onConfigUpdate(ScriptConfig config) { onUpdate.accept(config); }
    }

    @Nested
    class Profiling {

        private static final int LOOPS = 3;
        private static final long AWAIT_MS = 2000;

        @Test
        void everyLoop_isRecordedInTheProfiler_andTheStartIsTimed() {
            AtomicInteger loops = new AtomicInteger();
            ManagementScript script = new ManagementScript() {
                @Override public void onStart(ManagementContext ctx) {}
                @Override public int onLoop() { return loops.incrementAndGet() < LOOPS ? 1 : -1; }
                @Override public void onStop() {}
            };
            ManagementScriptRunner runner = new ManagementScriptRunner(script, mock(ManagementContext.class));
            assertNull(runner.lastStartedAt(), "never started");

            runner.start();
            assertTrue(runner.awaitStop(AWAIT_MS));

            assertAll(
                    () -> assertEquals(LOOPS, runner.getProfiler().getLoopCount()),
                    () -> assertTrue(runner.getProfiler().avgLoopMs() >= 0),
                    () -> assertNotNull(runner.lastStartedAt()));
        }
    }
}
