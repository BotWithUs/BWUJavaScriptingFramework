package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ScriptManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptRuntimeTest {

    private static final String ACCOUNT_UUID = "0f6b3c52-1d7e-4a8e-9b1f-5c2d7e8a9b10";
    private static final long WAIT_S = 5;

    @ScriptManifest(name = "TestScript", version = "1.0", author = "test")
    static class TestScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return 100; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    @Test
    void registerScript() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);

        ScriptRunner runner = runtime.registerScript(new TestScript());
        assertNotNull(runner);
        assertEquals(1, runtime.getRunners().size());
        assertFalse(runner.isRunning());
    }

    @Test
    void registerScript_isIdempotentByName() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);

        // Two distinct instances of the same-named script — e.g. a manual reload
        // followed by the auto-start probe re-registering the full set without a
        // preceding stopAll(). The list must not hold both.
        ScriptRunner first = runtime.registerScript(new TestScript());
        ScriptRunner second = runtime.registerScript(new TestScript());

        assertSame(first, second);
        assertEquals(1, runtime.getRunners().size());
    }

    @Test
    void findRunner() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.registerScript(new TestScript());

        assertNotNull(runtime.findRunner("TestScript"));
        assertNull(runtime.findRunner("NonExistent"));
    }

    @Test
    void startAndStopScript() throws Exception {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.startScript(new TestScript());

        assertEquals(1, runtime.getRunners().size());
        assertTrue(runtime.getRunners().get(0).isRunning());

        runtime.stopAll();
        Thread.sleep(100);
        assertEquals(0, runtime.getRunners().size());
    }

    @Test
    void removeScript() {
        ScriptContext ctx = mock(ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(ctx);
        runtime.registerScript(new TestScript());

        assertTrue(runtime.removeScript("TestScript"));
        assertEquals(0, runtime.getRunners().size());
    }

    /**
     * A uuid that arrives while a registration is in flight must still reach the
     * runner that registration creates. The registration is parked at the point
     * where its runner is fully configured but not yet in the runner list — the
     * window in which a uuid write that skips the registration lock updates
     * every runner except the new one.
     */
    @Test
    void setAccountUuid_duringRegistration_reachesTheNewRunner() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.setBeforeRunnerPublished(() -> {
            parked.countDown();
            awaitQuietly(release);
        });
        AtomicReference<ScriptRunner> registered = new AtomicReference<>();
        Thread registrar = Thread.ofPlatform().name("test-registrar")
                .start(() -> registered.set(runtime.registerScript(new TestScript())));
        assertTrue(parked.await(WAIT_S, TimeUnit.SECONDS), "registration never reached the seam");

        Thread setter = Thread.ofPlatform().name("test-uuid-setter")
                .start(() -> runtime.setAccountUuid(ACCOUNT_UUID));
        awaitBlockedOrFinished(setter);
        release.countDown();
        registrar.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        setter.join(TimeUnit.SECONDS.toMillis(WAIT_S));

        assertAll(
                () -> assertFalse(registrar.isAlive(), "registration did not finish"),
                () -> assertFalse(setter.isAlive(), "setAccountUuid did not finish"),
                () -> assertEquals(ACCOUNT_UUID, runtime.getAccountUuid()),
                () -> assertEquals(ACCOUNT_UUID, registered.get().getAccountUuid(),
                        "the runner registered during the uuid write was left without it"));
    }

    /**
     * Waits until {@code t} is either parked on a monitor or done. Either way the
     * uuid write has gone as far as it can before the registration is released.
     */
    private static void awaitBlockedOrFinished(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (t.isAlive() && t.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail(t.getName() + " neither blocked nor finished");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
