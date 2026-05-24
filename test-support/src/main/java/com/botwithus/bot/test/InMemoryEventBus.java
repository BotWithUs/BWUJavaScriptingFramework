package com.botwithus.bot.test;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.GameEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tests-only {@link EventBus} that delivers events synchronously to all
 * registered listeners.
 *
 * <p>Algorithm port of {@code EventBusImpl} in core, but with no core
 * dependency — {@code test-support} only depends on {@code api}. Concurrent
 * data structures are kept so tests that exercise event publishing from
 * multiple threads do not need their own synchronization.</p>
 *
 * <p>This bus does not throttle, batch, or reorder events; it does not
 * track metrics; it does not catch listener exceptions. Listener errors
 * surface to the {@link #publish} caller, which is the right default for
 * a test bus where you want failures to bubble.</p>
 */
public final class InMemoryEventBus implements EventBus {

    private final Map<Class<? extends GameEvent>, List<Consumer<? extends GameEvent>>> listeners
            = new ConcurrentHashMap<>();

    public InMemoryEventBus() {
    }

    @Override
    public <T extends GameEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public <T extends GameEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfPresent(eventType, (key, list) -> {
            list.remove(listener);
            return list.isEmpty() ? null : list;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void publish(GameEvent event) {
        List<Consumer<? extends GameEvent>> list = listeners.get(event.getClass());
        if (list == null) {
            return;
        }
        for (Consumer<? extends GameEvent> listener : list) {
            ((Consumer<GameEvent>) listener).accept(event);
        }
    }
}
