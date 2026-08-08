package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A script must not be able to publish, request or respond under another
 * script's name: the runtime stamps the sender it knows.
 */
class IdentifiedMessageBusTest {

    private static final String REAL = "honest-script";
    private static final String FORGED = "some-other-script";

    @Test
    @DisplayName("publish reports the runtime's identity, not the caller's string")
    void publishStampsRuntimeIdentity() throws InterruptedException {
        MessageBusImpl shared = new MessageBusImpl();
        MessageBus scoped = new IdentifiedMessageBus(shared, REAL);

        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        shared.subscribe("chan", message -> {
            seen.set(message.sender());
            delivered.countDown();
        });

        scoped.publish("chan", FORGED, "payload");

        assertTrue(delivered.await(5, TimeUnit.SECONDS), "message was delivered");
        assertEquals(REAL, seen.get(), "forged sender must be replaced");
    }

    @Test
    @DisplayName("request reports the runtime's identity")
    void requestStampsRuntimeIdentity() throws InterruptedException {
        MessageBusImpl shared = new MessageBusImpl();
        MessageBus scoped = new IdentifiedMessageBus(shared, REAL);

        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        shared.subscribe("chan", message -> {
            seen.set(message.sender());
            delivered.countDown();
        });

        scoped.request("chan", FORGED, "payload", 5_000);

        assertTrue(delivered.await(5, TimeUnit.SECONDS), "request was delivered");
        assertEquals(REAL, seen.get(), "forged sender must be replaced");
    }

    @Test
    @DisplayName("respond reports the runtime's identity")
    void respondStampsRuntimeIdentity() throws Exception {
        MessageBusImpl shared = new MessageBusImpl();
        MessageBus scoped = new IdentifiedMessageBus(shared, REAL);

        AtomicReference<String> requestId = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        shared.subscribe("chan", message -> {
            requestId.set(message.requestId());
            delivered.countDown();
        });

        CompletableFuture<ScriptMessage> pending =
                shared.request("chan", "asker", "payload", 5_000);
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "request was delivered");

        scoped.respond(requestId.get(), "chan", FORGED, "answer");

        assertEquals(REAL, pending.get(5, TimeUnit.SECONDS).sender(),
                "forged sender must be replaced on the response too");
    }

    @Test
    @DisplayName("subscribe/unsubscribe pass through to the shared bus")
    void subscriptionsDelegate() throws InterruptedException {
        MessageBusImpl shared = new MessageBusImpl();
        MessageBus scoped = new IdentifiedMessageBus(shared, REAL);

        CountDownLatch delivered = new CountDownLatch(1);
        Consumer<ScriptMessage> handler = message -> delivered.countDown();

        scoped.subscribe("chan", handler);
        shared.publish("chan", "anyone", "payload");
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "subscription registered on the shared bus");

        scoped.unsubscribe("chan", handler);
        // Nothing to assert beyond not throwing; removal is exercised by the
        // shared bus's own tests.
    }
}
