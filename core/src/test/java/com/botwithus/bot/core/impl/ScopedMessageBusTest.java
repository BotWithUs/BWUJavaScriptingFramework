package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedMessageBusTest {

    private static final long DELIVERY_TIMEOUT_SECONDS = 5L;

    /**
     * Records what the scope registers with the shared bus, so a test can invoke a
     * registered handler by hand. That is the only way to exercise the in-flight
     * case deterministically: the real bus dispatches on a virtual thread, so
     * "delivery started, then the script stopped" cannot be staged by timing.
     */
    private static final class RecordingBus implements MessageBus {

        private final List<Consumer<ScriptMessage>> registered = new ArrayList<>();
        private final List<String> outbound = new ArrayList<>();

        @Override
        public void subscribe(String channel, Consumer<ScriptMessage> handler) {
            registered.add(handler);
        }

        @Override
        public void unsubscribe(String channel, Consumer<ScriptMessage> handler) {
            registered.remove(handler);
        }

        @Override
        public void publish(String channel, String sender, Object payload) {
            outbound.add(channel);
        }

        @Override
        public CompletableFuture<ScriptMessage> request(String channel, String sender,
                                                        Object payload, long timeoutMs) {
            outbound.add(channel);
            return new CompletableFuture<>();
        }

        @Override
        public void respond(String requestId, String channel, String sender, Object payload) {
            outbound.add(channel);
        }
    }

    private static ScriptMessage anyMessage() {
        return new ScriptMessage("ch", "someone-else", "payload", 0L);
    }

    @Test
    @DisplayName("subscriptions work exactly as on the shared bus while running")
    void delegatesWhileRunning() throws Exception {
        MessageBusImpl shared = new MessageBusImpl();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        CountDownLatch delivered = new CountDownLatch(1);

        scoped.subscribe("ch", message -> delivered.countDown());
        shared.publish("ch", "someone-else", "payload");

        assertTrue(delivered.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "a running script must receive messages published on the shared bus");
    }

    @Test
    @DisplayName("unsubscribeAll drops handlers the script registered as lambdas")
    void unsubscribeAllRemovesLambdaHandlers() {
        // The point of the class: MessageBus.unsubscribe needs the exact Consumer
        // back, which a script that subscribed with a lambda cannot produce. Its
        // handlers would otherwise keep firing long after the script stopped.
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);

        scoped.subscribe("ch", message -> { });
        scoped.subscribe("other", message -> { });
        assertEquals(2, shared.registered.size());

        scoped.unsubscribeAll();

        assertEquals(0, shared.registered.size(), "no handler should be left on the shared bus");
    }

    @Test
    @DisplayName("a delivery already in flight is silenced once the scope is released")
    void inFlightDeliveryIsSilencedAfterRelease() {
        // MessageBusImpl dispatches on a fresh virtual thread per handler, so a
        // delivery picked up microseconds before teardown can still be waiting to
        // run after it. Unsubscribing alone does not cover that; the gate does.
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        AtomicInteger seen = new AtomicInteger();

        scoped.subscribe("ch", message -> seen.incrementAndGet());
        Consumer<ScriptMessage> inFlight = shared.registered.getFirst();
        assertNotNull(inFlight);

        scoped.unsubscribeAll();
        inFlight.accept(anyMessage());

        assertEquals(0, seen.get(), "a stopped script's handler must not run");
    }

    @Test
    @DisplayName("unsubscribeAll leaves another script's handlers alone")
    void unsubscribeAllIsScopedToItsOwnScript() throws Exception {
        MessageBusImpl shared = new MessageBusImpl();
        ScopedMessageBus mine = new ScopedMessageBus(shared);
        ScopedMessageBus theirs = new ScopedMessageBus(shared);
        AtomicInteger myHits = new AtomicInteger();
        CountDownLatch theirDelivery = new CountDownLatch(1);

        mine.subscribe("ch", message -> myHits.incrementAndGet());
        theirs.subscribe("ch", message -> theirDelivery.countDown());

        mine.unsubscribeAll();
        shared.publish("ch", "someone-else", "payload");

        assertTrue(theirDelivery.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "…and must not silence a sibling script's");
        assertEquals(0, myHits.get(), "stopping one script must silence its own handlers");
    }

    @Test
    @DisplayName("explicit unsubscribe removes the handler the script passed in")
    void unsubscribeRemovesTheScriptsOwnHandler() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        Consumer<ScriptMessage> handler = message -> { };

        scoped.subscribe("ch", handler);
        scoped.unsubscribe("ch", handler);

        assertEquals(0, shared.registered.size(),
                "the gated wrapper, not the raw handler, is what the shared bus holds");
    }

    @Test
    @DisplayName("the same handler on two channels is undone once per channel")
    void unsubscribeIsPerChannel() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        Consumer<ScriptMessage> handler = message -> { };

        scoped.subscribe("a", handler);
        scoped.subscribe("b", handler);
        scoped.unsubscribe("a", handler);

        assertEquals(1, shared.registered.size(),
                "unsubscribing one channel must not drop the other");
    }

    @Test
    @DisplayName("a released scope can no longer publish, request or respond")
    void releaseSilencesOutboundTraffic() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);

        scoped.unsubscribeAll();
        scoped.publish("ch", "me", "payload");
        scoped.respond("req-1", "ch", "me", "payload");
        CompletableFuture<ScriptMessage> pending = scoped.request("ch", "me", "payload", 1000L);

        assertEquals(List.of(), shared.outbound,
                "a thread the stopped script left behind must not reach other scripts");
        assertTrue(pending.isCompletedExceptionally(), "request must fail rather than hang");
    }

    @Test
    @DisplayName("unsubscribe ignores a handler this scope never registered")
    void unsubscribeIgnoresAForeignHandler() {
        // The shared bus holds gated wrappers, so forwarding a raw handler could
        // only ever hit a subscription belonging to somebody else — a management
        // script, which subscribes to the same bus unscoped, or another script.
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus mine = new ScopedMessageBus(shared);
        ScopedMessageBus theirs = new ScopedMessageBus(shared);
        Consumer<ScriptMessage> theirHandler = message -> { };

        theirs.subscribe("ch", theirHandler);
        mine.unsubscribe("ch", theirHandler);

        assertEquals(1, shared.registered.size(),
                "one script must not be able to unsubscribe another's handler");
    }

    @Test
    @DisplayName("a released scope can no longer unsubscribe on the shared bus")
    void unsubscribeAfterReleaseIsIgnored() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        ScopedMessageBus sibling = new ScopedMessageBus(shared);
        Consumer<ScriptMessage> handler = message -> { };
        sibling.subscribe("ch", handler);

        scoped.unsubscribeAll();
        scoped.unsubscribe("ch", handler);

        assertEquals(1, shared.registered.size());
    }

    @Test
    @DisplayName("subscribing after release never reaches the shared bus")
    void subscribeAfterReleaseIsIgnored() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);

        scoped.unsubscribeAll();
        scoped.subscribe("ch", message -> { });

        assertEquals(0, shared.registered.size());
    }

    @Test
    @DisplayName("unsubscribeAll is safe to call twice")
    void unsubscribeAllIsIdempotent() {
        RecordingBus shared = new RecordingBus();
        ScopedMessageBus scoped = new ScopedMessageBus(shared);
        scoped.subscribe("ch", message -> { });

        scoped.unsubscribeAll();
        scoped.unsubscribeAll();

        assertEquals(0, shared.registered.size());
    }
}
