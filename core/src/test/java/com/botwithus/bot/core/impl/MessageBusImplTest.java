package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.ScriptMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusImplTest {

    private MessageBusImpl bus;

    @BeforeEach
    void setUp() {
        bus = new MessageBusImpl();
    }

    @Test
    void publishAndSubscribe() throws Exception {
        AtomicReference<ScriptMessage> received = new AtomicReference<>();
        bus.subscribe("test-channel", received::set);
        bus.publish("test-channel", "sender1", "payload");

        // Delivery is async via virtual threads
        Thread.sleep(100);
        assertNotNull(received.get());
        assertEquals("test-channel", received.get().channel());
        assertEquals("sender1", received.get().sender());
        assertEquals("payload", received.get().payload());
    }

    @Test
    void unsubscribe() throws Exception {
        AtomicReference<ScriptMessage> received = new AtomicReference<>();
        var handler = new Consumer<ScriptMessage>() {
            @Override public void accept(ScriptMessage m) { received.set(m); }
        };
        bus.subscribe("ch", handler);
        bus.unsubscribe("ch", handler);
        bus.publish("ch", "s", "p");

        Thread.sleep(100);
        assertNull(received.get());
    }

    @Test
    void requestResponse() throws Exception {
        // Subscribe a handler that responds to requests
        bus.subscribe("req-channel", msg -> {
            if (msg.isRequest()) {
                bus.respond(msg.requestId(), msg.channel(), "responder", "response-data");
            }
        });

        CompletableFuture<ScriptMessage> future = bus.request("req-channel", "requester", "request-data", 5000);
        ScriptMessage response = future.get(5, TimeUnit.SECONDS);

        assertNotNull(response);
        assertEquals("response-data", response.payload());
        assertEquals("responder", response.sender());
    }

    @Test
    void requestTimeout() {
        // No handler - should timeout
        CompletableFuture<ScriptMessage> future = bus.request("empty-channel", "sender", "data", 100);

        assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void requestTimeoutIsClamped() {
        // orTimeout(Long.MAX_VALUE) never fires, so an unanswered request would pin
        // its pendingRequests entry for the life of the host. Both ends are clamped
        // into the range where the timeout actually expires.
        long unbounded = MessageBusImpl.clampRequestTimeout(Long.MAX_VALUE);
        assertTrue(unbounded < Long.MAX_VALUE, "an unbounded timeout must be capped");
        assertTrue(unbounded > 0, "the cap must still be a usable timeout");
        assertTrue(MessageBusImpl.clampRequestTimeout(0) > 0, "a zero timeout must still expire");
        assertTrue(MessageBusImpl.clampRequestTimeout(-1) > 0,
                "a negative timeout must still expire");
        assertEquals(1000L, MessageBusImpl.clampRequestTimeout(1000L),
                "an ordinary timeout must pass through untouched");
    }
}
