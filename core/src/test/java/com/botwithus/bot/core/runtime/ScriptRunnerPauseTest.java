package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.runtime.ScriptPause;
import com.botwithus.bot.api.util.Timing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pause and resume on a real runner thread. Failure meaning: loops counted while paused (the
 * runner ignores the switch), no loops after resume (the wake is lost), a stop that does not
 * end a paused runner, or a Timing wait on the script thread that does not see the switch.
 */
class ScriptRunnerPauseTest {

    private static final int LOOP_DELAY_MS = 10;
    private static final long SETTLE_MS = 200;
    private static final long WAIT_MS = 2_000;

    private ScriptRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
            runner.awaitStop(WAIT_MS);
        }
    }

    @Test
    void paused_runnerCallsNoLoop_untilResumed() throws Exception {
        AtomicInteger loops = new AtomicInteger();
        runner = new ScriptRunner(script(() -> loops.incrementAndGet()), mock(ScriptContext.class));
        runner.start();
        assertTrue(Timing.delayUntil(() -> loops.get() > 2, WAIT_MS));

        runner.pause();
        Thread.sleep(SETTLE_MS);
        int atPause = loops.get();
        Thread.sleep(SETTLE_MS);

        assertTrue(runner.isPaused());
        assertEquals(atPause, loops.get(), "no loop runs while paused");

        runner.resume();
        assertTrue(Timing.delayUntil(() -> loops.get() > atPause + 2, WAIT_MS));
        assertFalse(runner.isPaused());
    }

    @Test
    void stop_endsAPausedRunner() {
        runner = new ScriptRunner(script(() -> { }), mock(ScriptContext.class));
        runner.start();
        runner.pause();

        runner.stop();

        assertTrue(runner.awaitStop(WAIT_MS), "a stop wakes the paused loop");
    }

    @Test
    void scriptThread_seesTheRunnersSwitch_throughScriptPauseCurrent() {
        AtomicReference<ScriptPause> seen = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        runner = new ScriptRunner(script(() -> {
            seen.set(ScriptPause.current().orElse(null));
            recorded.set(true);
        }), mock(ScriptContext.class));
        runner.start();

        assertTrue(Timing.delayUntil(recorded::get, WAIT_MS));
        assertSame(runner.livenessState().pauseGate(), seen.get());
    }

    @Test
    void restart_clearsAPause() {
        runner = new ScriptRunner(script(() -> { }), mock(ScriptContext.class));
        runner.pause();

        runner.start();

        assertFalse(runner.isPaused());
    }

    private static BotScript script(Runnable eachLoop) {
        return new BotScript() {
            @Override
            public void onStart(ScriptContext ctx) {
            }

            @Override
            public int onLoop() {
                eachLoop.run();
                return LOOP_DELAY_MS;
            }

            @Override
            public void onStop() {
            }
        };
    }
}
