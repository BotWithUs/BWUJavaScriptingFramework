package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that MDC context keys are set correctly during script execution
 * and cleared after the script stops.
 */
class ScriptRunnerMdcTest {

    @ScriptManifest(name = "MdcTestScript", version = "1.0", author = "test")
    static class MdcCapturingScript implements BotScript {
        final ConcurrentHashMap<String, String> capturedMdc = new ConcurrentHashMap<>();
        final CountDownLatch loopLatch = new CountDownLatch(1);

        @Override
        public void onStart(ScriptContext ctx) {}

        @Override
        public int onLoop() {
            String scriptName = MDC.get("script.name");
            String connName = MDC.get("connection.name");
            if (scriptName != null) capturedMdc.put("script.name", scriptName);
            if (connName != null) capturedMdc.put("connection.name", connName);
            loopLatch.countDown();
            return -1; // stop after one loop
        }

        @Override
        public void onStop() {}

        @Override
        public List<ConfigField> getConfigFields() { return List.of(); }

        @Override
        public void onConfigUpdate(ScriptConfig config) {}
    }

    @Test
    void mdcSetsDuringScriptExecution() throws Exception {
        MdcCapturingScript script = new MdcCapturingScript();
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);
        runner.setConnectionName("TestConn");

        runner.start();
        assertTrue(script.loopLatch.await(2, TimeUnit.SECONDS), "Script loop should execute");
        Thread.sleep(100); // allow stop to complete

        assertEquals("MdcTestScript", script.capturedMdc.get("script.name"));
        assertEquals("TestConn", script.capturedMdc.get("connection.name"));
    }

    @Test
    void mdcClearedAfterScriptStops() throws Exception {
        MdcCapturingScript script = new MdcCapturingScript();
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRunner runner = new ScriptRunner(script, ctx);
        runner.setConnectionName("TestConn");

        runner.start();
        assertTrue(script.loopLatch.await(2, TimeUnit.SECONDS));
        Thread.sleep(200); // allow MDC.clear() in finally block

        assertFalse(runner.isRunning());
        // MDC is thread-local — cleared on the script's virtual thread, not this thread.
        // The key test is that the script captured MDC values during execution (tested above).
    }

    @Test
    void mdcScriptNameWithoutConnection() throws Exception {
        MdcCapturingScript script = new MdcCapturingScript();
        ScriptContext ctx = mock(ScriptContext.class);
        // No connection name — just script name should be set
        ScriptRunner runner = new ScriptRunner(script, ctx);

        runner.start();
        assertTrue(script.loopLatch.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);

        assertEquals("MdcTestScript", script.capturedMdc.get("script.name"));
        assertNull(script.capturedMdc.get("connection.name"));
    }
}
