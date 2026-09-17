package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.event.BreakEndedEvent;
import com.botwithus.bot.api.event.TickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EventBusImplTest {

    private EventBusImpl bus;

    @BeforeEach
    void setUp() {
        bus = new EventBusImpl();
    }

    @Test
    void subscribeAndPublish() {
        AtomicReference<Integer> received = new AtomicReference<>();
        bus.subscribe(TickEvent.class, e -> received.set(e.tick()));
        bus.publish(new TickEvent(42));
        assertEquals(42, received.get());
    }

    @Test
    void unsubscribe() {
        AtomicInteger count = new AtomicInteger();
        var listener = new Consumer<TickEvent>() {
            @Override public void accept(TickEvent e) {
                count.incrementAndGet();
            }
        };
        bus.subscribe(TickEvent.class, listener);
        bus.publish(new TickEvent(1));
        assertEquals(1, count.get());

        bus.unsubscribe(TickEvent.class, listener);
        bus.publish(new TickEvent(2));
        assertEquals(1, count.get());
    }

    @Test
    void publishToCorrectType() {
        AtomicInteger tickCount = new AtomicInteger();
        AtomicInteger breakCount = new AtomicInteger();
        bus.subscribe(TickEvent.class, e -> tickCount.incrementAndGet());
        bus.subscribe(BreakEndedEvent.class, e -> breakCount.incrementAndGet());

        bus.publish(new TickEvent(0));
        assertEquals(1, tickCount.get());
        assertEquals(0, breakCount.get());
    }

    @Test
    void eventCounts() {
        bus.subscribe(TickEvent.class, e -> {});
        bus.publish(new TickEvent(1));
        bus.publish(new TickEvent(2));

        var counts = bus.getEventCounts();
        assertEquals(2L, counts.get("TickEvent"));
    }

    @Test
    void subscriptionInfo() {
        bus.subscribe(TickEvent.class, e -> {});
        bus.subscribe(TickEvent.class, e -> {});

        var info = bus.getSubscriptionInfo();
        assertEquals(2, info.get("TickEvent"));
    }

    @Test
    void resetCounts() {
        bus.subscribe(TickEvent.class, e -> {});
        bus.publish(new TickEvent(1));
        assertFalse(bus.getEventCounts().isEmpty());

        bus.resetCounts();
        assertTrue(bus.getEventCounts().isEmpty());
    }
}
