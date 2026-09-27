package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The runtime tells the host's {@link RunnerListener} when a script starts,
 * stops and stalls, and when a management script crashes. Every test drives a
 * real runner on a real thread.
 */
class RunnerListenerTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final long WAIT_S = 5;
    /** How far past a watchdog threshold a synthetic sweep lands. */
    private static final long PAST_THRESHOLD_MS = 500L;

    /** Loops until released, ignoring both the running flag and interruption. */
    @ScriptManifest(name = "Spinner", version = "1.0", author = "test")
    private static final class Spinner implements BotScript {
        private final CountDownLatch inLoop = new CountDownLatch(1);
        private final AtomicBoolean release = new AtomicBoolean(false);

        @Override public void onStart(ScriptContext ctx) { }

        @Override public int onLoop() {
            inLoop.countDown();
            while (!release.get()) {
                Thread.onSpinWait();
            }
            return -1;
        }

        @Override public void onStop() { }
    }

    /** Runs one loop and asks to stop. */
    @ScriptManifest(name = "OneShot", version = "1.0", author = "test")
    private static final class OneShot implements BotScript {
        private final CountDownLatch looped = new CountDownLatch(1);

        @Override public void onStart(ScriptContext ctx) { }

        @Override public int onLoop() {
            looped.countDown();
            return -1;
        }

        @Override public void onStop() { }
    }

    @ScriptManifest(name = "BrokenManager", version = "1.0", author = "test")
    private static final class BrokenManager implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {
            throw new IllegalStateException("no orchestrator");
        }

        @Override public int onLoop() { return -1; }

        @Override public void onStop() { }
    }

    /** Records every call as a line, in arrival order. */
    private static final class Recording implements RunnerListener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        final BlockingQueue<LastCrash> crashes = new LinkedBlockingQueue<>();

        @Override public void scriptStarted(String connectionName, String scriptName) {
            calls.add("started " + connectionName + " " + scriptName);
        }

        @Override public void scriptStopped(String connectionName, String scriptName) {
            calls.add("stopped " + connectionName + " " + scriptName);
        }

        @Override public void scriptStalled(String connectionName, String scriptName) {
            calls.add("stalled " + connectionName + " " + scriptName);
        }

        @Override public void managementScriptCrashed(String scriptName, LastCrash crash) {
            calls.add("crashed " + scriptName);
            crashes.add(crash);
        }

        String next() throws InterruptedException {
            return calls.poll(WAIT_S, TimeUnit.SECONDS);
        }
    }

    private final Spinner spinner = new Spinner();

    @AfterEach
    void releaseSpinner() {
        spinner.release.set(true);
    }

    @Test
    void startAndStopReachTheListenerInOrder() throws Exception {
        Recording listener = new Recording();
        ScriptRuntime runtime = runtime();
        runtime.setRunnerListener(listener);
        OneShot script = new OneShot();

        runtime.startScript(script);

        assertEquals("started " + PIPE + " OneShot", listener.next());
        assertEquals("stopped " + PIPE + " OneShot", listener.next());
    }

    @Test
    void aListenerSetAfterRegistrationStillReachesTheRunner() throws Exception {
        Recording listener = new Recording();
        ScriptRuntime runtime = runtime();
        ScriptRunner runner = runtime.registerScript(new OneShot());

        runtime.setRunnerListener(listener);
        runner.start();

        assertEquals("started " + PIPE + " OneShot", listener.next());
    }

    @Test
    void aWatchdogStallReachesTheListener() throws Exception {
        Recording listener = new Recording();
        ScriptRuntime runtime = runtime();
        runtime.setRunnerListener(listener);
        runtime.startScript(spinner);
        assertTrue(spinner.inLoop.await(WAIT_S, TimeUnit.SECONDS), "script never looped");
        assertEquals("started " + PIPE + " Spinner", listener.next());

        ScriptRunner runner = runtime.findRunner("Spinner");
        runner.stop();
        runtime.sweep(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(LivenessWatchdog.STOP_STALL_MS + PAST_THRESHOLD_MS));

        assertEquals("stalled " + PIPE + " Spinner", listener.next());
    }

    @Test
    void aThrowingListenerDoesNotStopTheScript() throws Exception {
        RunnerListener throwing = new RunnerListener() {
            @Override public void scriptStarted(String connectionName, String scriptName) {
                throw new IllegalStateException("listener bug");
            }
        };
        ScriptRuntime runtime = runtime();
        runtime.setRunnerListener(throwing);
        OneShot script = new OneShot();

        runtime.startScript(script);

        assertTrue(script.looped.await(WAIT_S, TimeUnit.SECONDS),
                "a listener that throws must not keep the script out of its loop");
    }

    @Test
    void aManagementCrashReachesTheListener() throws Exception {
        Recording listener = new Recording();
        ManagementScriptRuntime runtime =
                new ManagementScriptRuntime(mock(ManagementContext.class), listener);

        runtime.startScript(new BrokenManager());

        assertEquals("crashed BrokenManager", listener.next());
        LastCrash crash = listener.crashes.poll(WAIT_S, TimeUnit.SECONDS);
        assertEquals(Phase.ON_START, crash.phase());
        assertEquals("no orchestrator", crash.cause().getMessage());
        assertNull(listener.calls.poll(), "only the crash is reported");
    }

    private static ScriptRuntime runtime() {
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(mock(Navigation.class));
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.setConnectionName(PIPE);
        return runtime;
    }
}
