package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.event.ChatMessageEvent;
import com.botwithus.bot.api.event.GameEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopedEventBusTest {

    private static GameEvent anyEvent() {
        return new ChatMessageEvent(null);
    }

    @Test
    @DisplayName("subscriptions work exactly as on the shared bus while running")
    void delegatesWhileRunning() {
        EventBusImpl shared = new EventBusImpl();
        ScopedEventBus scoped = new ScopedEventBus(shared);
        AtomicInteger seen = new AtomicInteger();

        scoped.subscribe(ChatMessageEvent.class, e -> seen.incrementAndGet());
        shared.publish(anyEvent());

        assertEquals(1, seen.get());
    }

    @Test
    @DisplayName("unsubscribeAll drops listeners the script registered as lambdas")
    void unsubscribeAllRemovesLambdaListeners() {
        // The point of the class: EventBus.unsubscribe needs the exact Consumer
        // back, which a script that subscribed with a lambda cannot produce. Its
        // listeners would otherwise keep firing on the event-pump thread — and
        // keep driving the game — long after the script stopped.
        EventBusImpl shared = new EventBusImpl();
        ScopedEventBus scoped = new ScopedEventBus(shared);
        AtomicInteger seen = new AtomicInteger();

        scoped.subscribe(ChatMessageEvent.class, e -> seen.incrementAndGet());
        scoped.subscribe(ChatMessageEvent.class, e -> seen.incrementAndGet());
        shared.publish(anyEvent());
        assertEquals(2, seen.get());

        scoped.unsubscribeAll();
        shared.publish(anyEvent());

        assertEquals(2, seen.get(), "no listener should fire after unsubscribeAll");
    }

    @Test
    @DisplayName("unsubscribeAll leaves another script's listeners alone")
    void unsubscribeAllIsScopedToItsOwnScript() {
        EventBusImpl shared = new EventBusImpl();
        ScopedEventBus mine = new ScopedEventBus(shared);
        ScopedEventBus theirs = new ScopedEventBus(shared);
        AtomicInteger myHits = new AtomicInteger();
        AtomicInteger theirHits = new AtomicInteger();

        mine.subscribe(ChatMessageEvent.class, e -> myHits.incrementAndGet());
        theirs.subscribe(ChatMessageEvent.class, e -> theirHits.incrementAndGet());

        mine.unsubscribeAll();
        shared.publish(anyEvent());

        assertEquals(0, myHits.get(), "stopping one script must silence its own listeners");
        assertEquals(1, theirHits.get(), "…and must not silence a sibling script's");
    }

    @Test
    @DisplayName("unsubscribeAll is safe to call twice")
    void unsubscribeAllIsIdempotent() {
        EventBusImpl shared = new EventBusImpl();
        ScopedEventBus scoped = new ScopedEventBus(shared);
        Consumer<ChatMessageEvent> listener = e -> { };
        scoped.subscribe(ChatMessageEvent.class, listener);

        scoped.unsubscribeAll();
        scoped.unsubscribeAll();

        assertEquals(0, shared.getSubscriptionInfo().getOrDefault("ChatMessageEvent", 0));
    }
}
