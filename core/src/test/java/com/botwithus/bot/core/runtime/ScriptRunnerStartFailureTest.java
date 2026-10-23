package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.event.ChatMessageEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A script whose {@code onStart} throws never runs, but its run still has to
 * end: the stop latch released, the host's teardown done, and the failure
 * reported once, as a crash. Every test drives a real runner on a real thread.
 */
class ScriptRunnerStartFailureTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String CAUSE = "start-boom";
    private static final String EVENT_TYPE = ChatMessageEvent.class.getSimpleName();
    private static final String ISC_CHANNEL = "start-failure-test";
    private static final long WAIT_MS = 5000L;

    /** Subscribes to both buses, then throws — the half-started script. */
    @ScriptManifest(name = "BadStart", version = "1.0", author = "test")
    private static final class BadStart implements BotScript {
        private final AtomicInteger onStopCalls = new AtomicInteger();

        @Override public void onStart(ScriptContext ctx) {
            ctx.getEventBus().subscribe(ChatMessageEvent.class, e -> { });
            ctx.getMessageBus().subscribe(ISC_CHANNEL, message -> { });
            throw new IllegalStateException(CAUSE);
        }

        @Override public int onLoop() {
            return -1;
        }

        @Override public void onStop() {
            onStopCalls.incrementAndGet();
        }
    }

    /** Records every listener call as a line, in arrival order. */
    private static final class Recording implements RunnerListener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();

        @Override public void scriptStarted(String connectionName, String scriptName) {
            calls.add("started " + scriptName);
        }

        @Override public void scriptStopped(String connectionName, String scriptName) {
            calls.add("stopped " + scriptName);
        }

        @Override public void scriptStalled(String connectionName, String scriptName) {
            calls.add("stalled " + scriptName);
        }
    }

    @Test
    void awaitStop_afterOnStartThrows_returnsTrue() {
        ScriptRunner runner = new ScriptRunner(new BadStart(), mockContext());

        runner.start();

        assertTrue(runner.awaitStop(WAIT_MS),
                "a run that failed to start never released its stop latch");
    }

    @Test
    void startFailure_leavesTheRunnerStoppedAndLive() {
        ScriptRunner runner = new ScriptRunner(new BadStart(), mockContext());

        runner.start();
        assertTrue(runner.awaitStop(WAIT_MS));

        assertAll(
                () -> assertFalse(runner.isRunning()),
                () -> assertFalse(runner.isThreadAlive()),
                () -> assertEquals(Liveness.LIVE, runner.liveness(),
                        "a start failure is not an escalation"));
    }

    @Test
    void startFailure_isRecordedAsExactlyOneCrash() {
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        List<LastCrash> handled = new CopyOnWriteArrayList<>();
        ScriptRunner runner = new ScriptRunner(new BadStart(), mockContext(),
                ConnectionContext::set, ConnectionContext::clear, events::add);
        runner.setErrorHandler(handled::add);

        runner.start();
        assertTrue(runner.awaitStop(WAIT_MS));

        LastCrash crash = runner.health().lastCrash().orElseThrow();
        assertAll(
                () -> assertEquals(1L, runner.health().totalCrashes()),
                () -> assertEquals(Phase.ON_START, crash.phase()),
                () -> assertEquals(CAUSE, crash.cause().getMessage()),
                () -> assertEquals(1, handled.size(), "error handler calls"),
                () -> assertEquals(1, events.size(), "published events"),
                () -> assertEquals(ScriptCrashedEvent.class, events.getFirst().getClass()));
    }

    @Test
    void startFailure_reachesTheListenerAsNeitherStartNorStop() {
        List<GameEvent> events = new CopyOnWriteArrayList<>();
        ScriptRuntime runtime = new ScriptRuntime(mockContext(),
                ConnectionContext::set, ConnectionContext::clear, events::add);
        runtime.setConnectionName(PIPE);
        Recording listener = new Recording();
        runtime.setRunnerListener(listener);

        runtime.startScript(new BadStart());
        assertTrue(runtime.findRunner("BadStart").awaitStop(WAIT_MS));

        // The crash leaves as a ScriptCrashedEvent; a started/stopped pair on
        // top of it would tell the host the script ran.
        assertAll(
                () -> assertEquals(List.of(), List.copyOf(listener.calls)),
                () -> assertEquals(1, events.size(), "the crash is the one thing reported"));
    }

    @Test
    void startFailure_releasesTheHostSideOfTheRun() {
        Navigation nav = mock(Navigation.class);
        ScriptContext ctx = mockContext(nav);
        AtomicInteger cleanerCalls = new AtomicInteger();
        BadStart script = new BadStart();
        ScriptRunner runner = new ScriptRunner(script, ctx,
                ConnectionContext::set, cleanerCalls::incrementAndGet, e -> { });

        runner.start();
        assertTrue(runner.awaitStop(WAIT_MS));

        verify(nav, times(1)).cleanup();
        assertAll(
                () -> assertEquals(1, cleanerCalls.get(), "connection cleaner calls"),
                () -> assertEquals(0, script.onStopCalls.get(),
                        "onStop pairs with a completed onStart; a script that never "
                                + "started is not stopped"));
    }

    @Test
    void startFailure_takesBackWhatOnStartSubscribed() {
        EventBusImpl events = new EventBusImpl();
        MessageBusImpl messages = new MessageBusImpl();
        ScriptRuntime runtime = new ScriptRuntime(
                new ScriptContextImpl(mock(GameAPI.class), events, messages));
        ScriptRunner runner = runtime.registerScript(new BadStart());

        runner.start();
        assertTrue(runner.awaitStop(WAIT_MS));

        assertAll(
                () -> assertEquals(0, events.getSubscriptionInfo().getOrDefault(EVENT_TYPE, 0),
                        "the event handler registered in onStart outlived the run"),
                () -> assertEquals(0, messages.getSubscriptionInfo().getOrDefault(ISC_CHANNEL, 0),
                        "the ISC handler registered in onStart outlived the run"));
    }

    @Test
    void stopAll_afterStartFailure_doesNotQuarantineTheRunner() {
        ScriptRuntime runtime = new ScriptRuntime(mockContext());
        ScriptRunner runner = runtime.registerScript(new BadStart());
        runner.start();
        awaitThreadExit(runner);

        runtime.stopAll();

        assertAll(
                () -> assertEquals(List.of(), runtime.getQuarantined(),
                        "a runner whose thread has exited is not a zombie"),
                () -> assertEquals(List.of(), runtime.getRunners()));
    }

    private static void awaitThreadExit(ScriptRunner runner) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (runner.isThreadAlive()) {
            if (System.nanoTime() > deadline) {
                fail("script thread never exited");
            }
            Thread.onSpinWait();
        }
    }

    private static ScriptContext mockContext() {
        return mockContext(mock(Navigation.class));
    }

    private static ScriptContext mockContext(Navigation nav) {
        ScriptContext ctx = mock(ScriptContext.class);
        when(ctx.getNavigation()).thenReturn(nav);
        when(ctx.getEventBus()).thenReturn(new EventBusImpl());
        when(ctx.getMessageBus()).thenReturn(new MessageBusImpl());
        return ctx;
    }
}
