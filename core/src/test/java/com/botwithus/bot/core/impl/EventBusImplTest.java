package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.event.GameEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventBusImplTest {

    static class TestEvent extends GameEvent {
        final String data;
        TestEvent(String data) {
            super("test");
            this.data = data;
        }
    }

    static class OtherEvent extends GameEvent {
        OtherEvent() {
            super("other");
        }
    }

    private EventBusImpl bus;

    @BeforeEach
    void setUp() {
        bus = new EventBusImpl();
    }

    @Test
    void subscribeAndPublish() {
        AtomicReference<String> received = new AtomicReference<>();
        bus.subscribe(TestEvent.class, e -> received.set(e.data));
        bus.publish(new TestEvent("hello"));
        assertEquals("hello", received.get());
    }

    @Test
    void unsubscribe() {
        AtomicInteger count = new AtomicInteger();
        var listener = new java.util.function.Consumer<TestEvent>() {
            @Override public void accept(TestEvent e) { count.incrementAndGet(); }
        };
        bus.subscribe(TestEvent.class, listener);
        bus.publish(new TestEvent("a"));
        assertEquals(1, count.get());

        bus.unsubscribe(TestEvent.class, listener);
        bus.publish(new TestEvent("b"));
        assertEquals(1, count.get());
    }

    @Test
    void publishToCorrectType() {
        AtomicInteger testCount = new AtomicInteger();
        AtomicInteger otherCount = new AtomicInteger();
        bus.subscribe(TestEvent.class, e -> testCount.incrementAndGet());
        bus.subscribe(OtherEvent.class, e -> otherCount.incrementAndGet());

        bus.publish(new TestEvent("x"));
        assertEquals(1, testCount.get());
        assertEquals(0, otherCount.get());
    }

    @Test
    void firstSubscribeHook() {
        AtomicReference<Class<?>> hookedType = new AtomicReference<>();
        bus.setSubscriptionHooks(hookedType::set, t -> {});
        bus.subscribe(TestEvent.class, e -> {});
        assertEquals(TestEvent.class, hookedType.get());
    }

    @Test
    void eventCounts() {
        bus.subscribe(TestEvent.class, e -> {});
        bus.publish(new TestEvent("a"));
        bus.publish(new TestEvent("b"));

        var counts = bus.getEventCounts();
        assertEquals(2L, counts.get("TestEvent"));
    }

    @Test
    void subscriptionInfo() {
        bus.subscribe(TestEvent.class, e -> {});
        bus.subscribe(TestEvent.class, e -> {});

        var info = bus.getSubscriptionInfo();
        assertEquals(2, info.get("TestEvent"));
    }

    @Test
    void resetCounts() {
        bus.subscribe(TestEvent.class, e -> {});
        bus.publish(new TestEvent("a"));
        assertFalse(bus.getEventCounts().isEmpty());

        bus.resetCounts();
        assertTrue(bus.getEventCounts().isEmpty());
    }
}
