package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers what happens when a script refuses to stop.
 *
 * <p>The escalation tests drive {@link ScriptRuntime#sweep(long)} with synthetic
 * timestamps rather than sleeping through the real grace windows, so they are
 * deterministic and fast.</p>
 */
class ScriptStopEnforcementTest {

    /** Channel used by the ISC teardown test; nothing else subscribes to it. */
    private static final String ISC_CHANNEL = "test.isc.teardown";

    /**
     * A script that ignores both the running flag and interruption — the exact
     * shape the watchdog exists for.
     *
     * <p>{@link #awaitInLoop()} matters: {@code runLoop} re-checks the running
     * flag at the top of each iteration, so a stop that lands before the thread
     * has entered {@code onLoop} exits cleanly and proves nothing. Tests must
     * wait for the script to actually be spinning before asking it to stop.</p>
     */
    @ScriptManifest(name = "Spinner", version = "1.0", author = "test",
            description = "ignores stop requests")
    private static final class Spinner implements BotScript {
        private final CountDownLatch inLoop = new CountDownLatch(1);
        private final AtomicBoolean release = new AtomicBoolean(false);

        @Override public void onStart(ScriptContext ctx) {}

        @Override public int onLoop() {
            inLoop.countDown();
            while (!release.get()) {
                Thread.onSpinWait();
            }
            return -1;
        }

        @Override public void onStop() {}

        void awaitInLoop() throws InterruptedException {
            assertTrue(inLoop.await(5, TimeUnit.SECONDS), "script never entered onLoop()");
        }

        void release() {
            release.set(true);
        }
    }

    /** Every spinner started by a test, released in teardown so none outlive it. */
    private final List<Spinner> spinners = new ArrayList<>();

    private Spinner newSpinner() {
        Spinner spinner = new Spinner();
        spinners.add(spinner);
        return spinner;
    }

    @AfterEach
    void releaseSpinners() {
        spinners.forEach(Spinner::release);
    }

    private ScriptContext mockContext() {
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(mock(Navigation.class));
        return ctx;
    }

    /** Starts a spinner on a fresh runtime and returns its runner, already looping. */
    private ScriptRunner startSpinner(ScriptRuntime runtime) throws InterruptedException {
        Spinner spinner = newSpinner();
        runtime.startScript(spinner);
        spinner.awaitInLoop();
        return runtime.findRunner("Spinner");
    }

    @Test
    @DisplayName("runaway scripts cannot starve the rest of the host")
    void runawayScriptsDoNotStarveTheHost() throws Exception {
        // The regression test for the headline bug. On virtual threads, enough
        // CPU-bound scripts occupy every carrier in the shared scheduler — they
        // are never preempted — and no other virtual thread in the JVM runs
        // again, including rpc-reader, which wedges RPC for every client. On
        // platform threads the OS preempts them and the host stays responsive.
        int count = Runtime.getRuntime().availableProcessors() + 2;
        List<ScriptRunner> runners = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Spinner spinner = newSpinner();
            ScriptRunner runner = new ScriptRunner(spinner, mockContext());
            runner.start();
            spinner.awaitInLoop();
            runners.add(runner);
        }

        CountDownLatch ranAnyway = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            vt.submit(ranAnyway::countDown);
            assertTrue(ranAnyway.await(10, TimeUnit.SECONDS),
                    "a virtual thread never got scheduled while " + count
                            + " scripts were spinning — script threads are starving the host");
        }

        releaseSpinners();
        for (ScriptRunner runner : runners) {
            runner.stop();
            assertTrue(runner.awaitStop(5000), "released spinner should drain");
        }
    }

    @Test
    @DisplayName("a script that ignores stop escalates STALLED -> REVOKED -> ABANDONED")
    void unstoppableScriptEscalates() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = startSpinner(runtime);

        runner.stop();
        long stopped = System.nanoTime();

        // Probe just past each production threshold rather than at hardcoded
        // times, so retuning a window can't leave this silently testing a
        // boundary that no longer exists.
        runtime.sweep(stopped + justPast(LivenessWatchdog.STOP_STALL_MS));
        assertEquals(Liveness.STALLED, runner.liveness(),
                "past the stop-stall window the runner should be flagged unresponsive");

        runtime.sweep(stopped + justPast(LivenessWatchdog.REVOKE_GRACE_MS));
        assertEquals(Liveness.REVOKED, runner.liveness(),
                "past the revoke grace the runner should lose access to the game");

        runtime.sweep(stopped + justPast(LivenessWatchdog.ABANDON_GRACE_MS));
        assertEquals(Liveness.ABANDONED, runner.liveness(),
                "past the abandon grace the runner should be written off");
    }

    /** Nanos corresponding to comfortably past a millisecond threshold. */
    private static long justPast(long thresholdMs) {
        return TimeUnit.MILLISECONDS.toNanos(thresholdMs + 500L);
    }

    @Test
    @DisplayName("the watchdog arms when a script is started directly, not only via startScript")
    void watchdogArmsOnDirectRunnerStart() throws Exception {
        // The CLI and the GUI both start scripts by resolving a runner and
        // calling start() on it. Arming the watchdog from the runtime's
        // startScript() only would leave it dead for every user-initiated
        // start, making the whole escalation path inert in normal use.
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        Spinner spinner = newSpinner();
        ScriptRunner runner = runtime.registerScript(spinner);
        runner.start();
        spinner.awaitInLoop();
        runner.stop();

        assertTrue(hasWatchdogThread(), "no script-watchdog thread after a direct runner start");
        runtime.sweep(System.nanoTime() + justPast(LivenessWatchdog.ABANDON_GRACE_MS));
        assertEquals(Liveness.ABANDONED, runner.liveness());
    }

    private static boolean hasWatchdogThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("script-watchdog"));
    }

    @Test
    @DisplayName("restarting a script clears the previous run's stop state")
    void restartClearsStopState() throws Exception {
        // A runner is reused across restarts. Leaving the old stop timestamp in
        // place makes the watchdog see a stop from long ago and quarantine the
        // freshly-started thread — and makes isStopRequested() true on its very
        // first loop, so a well-behaved script exits immediately.
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = runtime.registerScript(new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return 10; }
            @Override public void onStop() {}
        });
        runner.start();
        runner.stop();
        assertTrue(runner.awaitStop(2000));
        assertTrue(runner.isStopRequested());

        runner.start();

        assertFalse(runner.isStopRequested(), "a restarted run must not inherit the old stop");
        runtime.sweep(System.nanoTime() + justPast(LivenessWatchdog.ABANDON_GRACE_MS));
        assertEquals(Liveness.LIVE, runner.liveness(),
                "a healthy restarted script must not be quarantined");
        runner.stop();
        assertTrue(runner.awaitStop(2000));
    }

    @Test
    @DisplayName("a script that stops normally is never escalated")
    void wellBehavedScriptIsNotEscalated() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = runtime.registerScript(new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return 10; }
            @Override public void onStop() {}
        });
        runner.start();
        runner.stop();
        assertTrue(runner.awaitStop(2000));

        // Even far past every grace window: the thread drained, so there is
        // nothing to escalate. Guards against the watchdog flagging scripts
        // that did exactly what they were asked.
        runtime.sweep(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60_000));
        assertEquals(Liveness.LIVE, runner.liveness());
    }

    @Test
    @DisplayName("a runner that won't drain is quarantined, not dropped")
    void undrainableRunnerIsQuarantined() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = startSpinner(runtime);

        runtime.stopAll();

        // Dropping the reference is what used to make a runaway script
        // invisible while it carried on playing the game.
        assertEquals(List.of(runner), runtime.getQuarantined());
        assertTrue(runtime.getRunners().contains(runner),
                "a quarantined zombie must stay visible to the GUI and CLI");
        assertEquals(runner, runtime.findRunner("Spinner"),
                "a quarantined runner must stay addressable");
    }

    @Test
    @DisplayName("a quarantined script cannot be restarted on top of its live thread")
    void abandonedRunnerRefusesToRestart() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = startSpinner(runtime);
        runner.stop();
        runtime.sweep(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20_000));
        assertEquals(Liveness.ABANDONED, runner.liveness());

        runner.start();

        assertFalse(runner.isRunning(),
                "starting an abandoned runner would put two copies of the script on one client");
    }

    @Test
    @DisplayName("onStop can still block — teardown does not inherit the stop interrupt")
    void teardownRunsWithoutTheInterruptFlag() throws Exception {
        // stop() interrupts the script thread, so without clearing the flag for
        // the duration of teardown every blocking call in cleanup throws
        // InterruptedException immediately — including the join that waits for
        // the walk executor to quiesce, which is the whole point of the join.
        AtomicBoolean onStopCompleted = new AtomicBoolean(false);
        AtomicBoolean interruptSeen = new AtomicBoolean(false);
        CountDownLatch inLoop = new CountDownLatch(1);

        ScriptRunner runner = new ScriptRunner(new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() {
                inLoop.countDown();
                return 50;
            }
            @Override public void onStop() {
                interruptSeen.set(Thread.currentThread().isInterrupted());
                try {
                    Thread.sleep(20);
                    onStopCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, mockContext());
        runner.start();
        assertTrue(inLoop.await(5, TimeUnit.SECONDS));

        runner.stop();
        assertTrue(runner.awaitStop(5000));

        assertFalse(interruptSeen.get(), "teardown should not run with the interrupt flag set");
        assertTrue(onStopCompleted.get(), "a blocking call in onStop should not be cut short");
    }

    @Test
    @DisplayName("isStopRequested lets a script bail out from inside onLoop")
    void stopSignalIsVisibleToTheScript() throws Exception {
        AtomicBoolean sawStopRequest = new AtomicBoolean(false);
        CountDownLatch inLoop = new CountDownLatch(1);
        // Needs the real context: isStopRequested() is a default method, so a
        // mock would answer false forever and the script would never exit.
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(
                mock(GameAPI.class), new EventBusImpl(), new MessageBusImpl()));

        ScriptRunner runner = runtime.registerScript(new BotScript() {
            private ScriptContext ctx;

            @Override public void onStart(ScriptContext context) { this.ctx = context; }
            @Override public int onLoop() {
                inLoop.countDown();
                while (!ctx.isStopRequested()) {
                    Thread.onSpinWait();
                }
                sawStopRequest.set(true);
                return -1;
            }
            @Override public void onStop() {}
        });
        runner.start();
        assertTrue(inLoop.await(5, TimeUnit.SECONDS));

        runner.stop();

        assertTrue(runner.awaitStop(5000), "script polling isStopRequested should exit cleanly");
        assertTrue(sawStopRequest.get());
        assertEquals(Liveness.LIVE, runner.liveness(), "a clean exit is not an escalation");
    }

    @Test
    @DisplayName("a stopped script stops handling ISC messages")
    void stoppedScriptStopsHandlingIscMessages() throws Exception {
        // The wiring test for the ISC scope. ScopedMessageBusTest drives that class
        // directly; only this proves the runtime installs it and the runner releases
        // it. Nothing else would catch a dropped hook — the scope logs nothing on
        // teardown, so the failure mode is a script that quietly keeps handling
        // messages after Stop.
        MessageBusImpl shared = new MessageBusImpl();
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(
                mock(GameAPI.class), new EventBusImpl(), shared));
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch firstDelivery = new CountDownLatch(1);

        ScriptRunner runner = runtime.registerScript(new BotScript() {
            @Override public void onStart(ScriptContext context) {
                context.getMessageBus().subscribe(ISC_CHANNEL, message -> {
                    handled.incrementAndGet();
                    firstDelivery.countDown();
                });
                subscribed.countDown();
            }
            @Override public int onLoop() { return 10; }
            @Override public void onStop() {}
        });
        runner.start();
        // onStart runs on the script's own thread, so publishing before it has
        // subscribed would prove nothing.
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "onStart never ran");
        shared.publish(ISC_CHANNEL, "someone-else", "while-running");
        assertTrue(firstDelivery.await(5, TimeUnit.SECONDS),
                "a running script must receive ISC messages");

        runner.stop();
        assertTrue(runner.awaitStop(5000));

        assertEquals(0, shared.getSubscriptionInfo().getOrDefault(ISC_CHANNEL, 0),
                "stopping the script must take its handler off the shared bus");
        // Deterministic given the assertion above: with no handlers left, publish
        // returns without dispatching, so there is no race to wait out.
        int afterStop = handled.get();
        shared.publish(ISC_CHANNEL, "someone-else", "after-stop");
        assertEquals(afterStop, handled.get(), "a stopped script's handler must not fire");
    }
}
