package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The context a registered script is handed drives its own runner's pause switch and holds its
 * runner's settings. Failure meaning: {@code ctx.pause()} that leaves the runner running, or
 * {@code ctx.getSettings()} writing to a map the runner's config never sees.
 */
class ScriptControlsBindingTest {

    @ScriptManifest(name = "ControlsProbe", version = "1.0", author = "test")
    static final class Probe implements BotScript {
        final AtomicReference<ScriptContext> context = new AtomicReference<>();

        @Override
        public void onStart(ScriptContext ctx) {
            context.set(ctx);
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }

    @Test
    void scriptContext_pausesItsRunner_andSharesItsSettings() {
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(mock(GameAPI.class),
                new EventBusImpl(), new MessageBusImpl()));
        Probe probe = new Probe();
        ScriptRunner runner = runtime.registerScript(probe);
        runner.start();
        assertTrue(runner.awaitStop(5_000));
        ScriptContext ctx = probe.context.get();

        ctx.pause();
        assertTrue(runner.isPaused());
        assertTrue(ctx.isPaused());
        ctx.resume();
        assertFalse(runner.isPaused());

        ctx.getSettings().put("tripCount", "12");
        assertSame(runner.getSettings(), ctx.getSettings());
        assertEquals("12", runner.getCurrentConfig().asMap().get("tripCount"));
    }
}
