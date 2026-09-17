package com.botwithus.bot.test;

import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.TickEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryEventBusTest {

    private static final int SAMPLE_ACTION_ID = 7;
    private static final int SAMPLE_PARAM = 42;
    private static final int SAMPLE_TICK_ID = 1234;

    @Test
    void publish_deliversToSubscribedListeners() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ActionExecutedEvent> received = new ArrayList<>();
        bus.subscribe(ActionExecutedEvent.class, received::add);

        ActionExecutedEvent event = new ActionExecutedEvent(SAMPLE_ACTION_ID, SAMPLE_PARAM, 0, 0);
        bus.publish(event);

        assertEquals(List.of(event), received);
    }

    @Test
    void publish_ignoresListenersOfOtherTypes() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ActionExecutedEvent> actionEvents = new ArrayList<>();
        List<TickEvent> tickEvents = new ArrayList<>();
        bus.subscribe(ActionExecutedEvent.class, actionEvents::add);
        bus.subscribe(TickEvent.class, tickEvents::add);

        bus.publish(new TickEvent(SAMPLE_TICK_ID));

        assertAll(
                () -> assertTrue(actionEvents.isEmpty()),
                () -> assertEquals(1, tickEvents.size()));
    }

    @Test
    void unsubscribe_stopsDelivery() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<TickEvent> received = new ArrayList<>();
        Consumer<TickEvent> listener = received::add;
        bus.subscribe(TickEvent.class, listener);

        bus.publish(new TickEvent(1));
        bus.unsubscribe(TickEvent.class, listener);
        bus.publish(new TickEvent(2));

        assertEquals(1, received.size());
    }

    @Test
    void publish_unsubscribedType_isNoOp() {
        InMemoryEventBus bus = new InMemoryEventBus();
        bus.publish(new TickEvent(1));
    }
}
