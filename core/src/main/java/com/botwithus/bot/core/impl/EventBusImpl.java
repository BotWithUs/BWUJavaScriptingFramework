package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.GameEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class EventBusImpl implements EventBus {

    private final Map<Class<? extends GameEvent>, List<Consumer<? extends GameEvent>>> listeners = new ConcurrentHashMap<>();
    private final Map<Class<? extends GameEvent>, LongAdder> eventCounts = new ConcurrentHashMap<>();

    /** Called when the first listener registers for an event type. */
    private Consumer<Class<? extends GameEvent>> onFirstSubscribe;

    /** Called when the last listener unregisters for an event type. */
    private Consumer<Class<? extends GameEvent>> onLastUnsubscribe;

    /**
     * Sets callbacks for automatic server-side subscription management.
     * Called by the wiring layer so that subscribing to a typed event
     * automatically subscribes to the pipe server event.
     */
    public void setSubscriptionHooks(Consumer<Class<? extends GameEvent>> onFirst,
                                     Consumer<Class<? extends GameEvent>> onLast) {
        this.onFirstSubscribe = onFirst;
        this.onLastUnsubscribe = onLast;
    }

    @Override
    public <T extends GameEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.compute(eventType, (key, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            boolean wasEmpty = list.isEmpty();
            list.add(listener);
            if (wasEmpty && onFirstSubscribe != null) {
                onFirstSubscribe.accept(key);
            }
            return list;
        });
    }

    @Override
    public <T extends GameEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfPresent(eventType, (key, list) -> {
            list.remove(listener);
            if (list.isEmpty() && onLastUnsubscribe != null) {
                onLastUnsubscribe.accept(key);
            }
            return list.isEmpty() ? null : list;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void publish(GameEvent event) {
        eventCounts.computeIfAbsent(event.getClass(), k -> new LongAdder()).increment();
        List<Consumer<? extends GameEvent>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<? extends GameEvent> listener : list) {
                ((Consumer<GameEvent>) listener).accept(event);
            }
        }
    }

    public Map<String, Integer> getSubscriptionInfo() {
        return listeners.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().getSimpleName(),
                        e -> e.getValue().size()
                ));
    }

    public Map<String, Long> getEventCounts() {
        return eventCounts.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().getSimpleName(),
                        e -> e.getValue().sum()
                ));
    }

    public void resetCounts() {
        eventCounts.clear();
    }
}
