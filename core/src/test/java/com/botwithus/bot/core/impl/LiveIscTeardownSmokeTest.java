package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.isc.ScriptMessage;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ConnectionContext;
import com.botwithus.bot.core.runtime.ScriptGate;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live smoke test for ISC handler teardown: a stopped script must stop handling
 * messages on the connection's shared {@link MessageBusImpl}.
 *
 * <p>The unit tests cover the mechanism and the in-process wiring test covers
 * the runtime hook, but both build the context by hand around a mocked
 * {@code GameAPI}. This one stands up the same objects
 * {@code JBotApplication.main} does — real RPC client over the real pipe, real
 * event pump over the real mapping, real {@code GameAPIImpl}, and therefore the
 * real {@link Walker} whose {@code cleanup()} runs in the same teardown path.
 * Order matters there: the subscriptions are released between {@code onStop()}
 * and the navigation cleanup, so a throw or a block in the neighbouring phases
 * is exactly what an isolated test cannot see.</p>
 *
 * <p>Two scripts, not one. Stopping a single script would stop its own
 * publishing too, so a silent channel afterwards would prove nothing: the
 * publisher has to keep running to show the subscriber genuinely stopped
 * listening. The wait after the stop is gated on the publisher's own counter
 * rather than on elapsed time, so the test is not a race against a sleep.</p>
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}.
 * Requires NXTLibrary injected into a running game client.</p>
 */
class LiveIscTeardownSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveIscTeardownSmokeTest.class);

    private static final String CHANNEL = "smoke.isc.teardown";
    private static final int LOOP_DELAY_MS = 50;
    /** Publishes to observe after the stop before believing the handler is gone. */
    private static final int PUBLISHES_AFTER_STOP = 20;
    private static final long AWAIT_SECONDS = 15L;
    private static final long STOP_TIMEOUT_MS = 5000L;

    private static final AtomicInteger PUBLISHED = new AtomicInteger();
    private static final AtomicInteger HANDLED = new AtomicInteger();
    private static final CountDownLatch FIRST_DELIVERY = new CountDownLatch(1);

    @ScriptManifest(name = "ISC Publisher", version = "1.0", author = "test",
            description = "publishes on the probe channel every loop")
    static final class IscPublisher implements BotScript {
        private ScriptContext ctx;

        @Override public void onStart(ScriptContext context) {
            this.ctx = context;
        }

        @Override public int onLoop() {
            ctx.getMessageBus().publish(CHANNEL, "ignored-the-runtime-stamps-it",
                    PUBLISHED.incrementAndGet());
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}
    }

    @ScriptManifest(name = "ISC Subscriber", version = "1.0", author = "test",
            description = "counts messages on the probe channel")
    static final class IscSubscriber implements BotScript {
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override public void onStart(ScriptContext context) {
            context.getMessageBus().subscribe(CHANNEL, this::onMessage);
            subscribed.countDown();
        }

        private void onMessage(ScriptMessage message) {
            HANDLED.incrementAndGet();
            FIRST_DELIVERY.countDown();
        }

        @Override public int onLoop() {
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}

        void awaitSubscribed() throws InterruptedException {
            assertTrue(subscribed.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onStart never ran");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void stoppedScriptStopsHandlingIscMessages() throws Exception {
        List<String> pipes = PipeClient.scanPipes();
        if (pipes.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the DLL into a running game first");
        }
        String pipeName = pipes.getFirst();
        long pid = SharedRegion.parsePid(pipeName).orElseThrow();
        log.info("Connected to {} (pid={})", pipeName, pid);

        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            EventBusImpl eventBus = new EventBusImpl();
            MessageBusImpl messageBus = new MessageBusImpl();
            try (SharedRegionEventPump pump = new SharedRegionEventPump(pid, eventBus::publish)) {
                GameAPIImpl gameAPI = new GameAPIImpl(rpc, null,
                        () -> new GameSnapshotImpl(pump.region().snapshot()),
                        new StubGuard(), eventBus::publish, GamevalIndex.empty());
                rpc.start();
                runProbe(new ScriptContextImpl(gameAPI, eventBus, messageBus), messageBus, rpc);
                rpc.close();
            }
        }
    }

    private void runProbe(ScriptContextImpl context, MessageBusImpl shared, RpcClient rpc)
            throws Exception {
        ScriptRuntime runtime = new ScriptRuntime(context,
                ConnectionContext::set, ConnectionContext::clear);
        ScriptGate gate = new ScriptGate();
        runtime.setScriptGate(gate);
        rpc.setScriptGate(gate);

        IscSubscriber subscriberScript = new IscSubscriber();
        ScriptRunner publisher = runtime.registerScript(new IscPublisher());
        ScriptRunner subscriber = runtime.registerScript(subscriberScript);
        subscriber.start();
        subscriberScript.awaitSubscribed();
        publisher.start();

        try {
            assertTrue(FIRST_DELIVERY.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "a running script must receive ISC messages over the live host");
            log.info("BEFORE STOP  published={} handled={} subscribers={}",
                    PUBLISHED.get(), HANDLED.get(), shared.getSubscriptionInfo());

            subscriber.stop();
            assertTrue(subscriber.awaitStop(STOP_TIMEOUT_MS), "subscriber did not drain");

            int handledAtStop = HANDLED.get();
            awaitFurtherPublishes();
            log.info("AFTER STOP   published={} handled={} (was {}) subscribers={}",
                    PUBLISHED.get(), HANDLED.get(), handledAtStop, shared.getSubscriptionInfo());

            assertEquals(0, shared.getSubscriptionInfo().getOrDefault(CHANNEL, 0),
                    "stopping the script must take its handler off the connection's bus");
            assertEquals(handledAtStop, HANDLED.get(),
                    "a stopped script's ISC handler must not fire while a sibling keeps publishing");
            assertEquals(Liveness.LIVE, subscriber.liveness(), "a clean stop is not an escalation");
            assertTrue(runtime.getQuarantined().isEmpty(), "nothing should have been quarantined");
        } finally {
            runtime.stopAll();
        }
    }

    /** Blocks until the publisher has pushed enough further messages to be conclusive. */
    private void awaitFurtherPublishes() throws InterruptedException {
        int target = PUBLISHED.get() + PUBLISHES_AFTER_STOP;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (PUBLISHED.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(LOOP_DELAY_MS);
        }
        assertTrue(PUBLISHED.get() >= target,
                "publisher stalled at " + PUBLISHED.get() + "; the check needs it still running");
    }
}
