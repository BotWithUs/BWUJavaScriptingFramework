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

    public EventBusImpl() {}

    private final Map<Class<? extends GameEvent>, List<Consumer<? extends GameEvent>>> listeners = new ConcurrentHashMap<>();
    private final Map<Class<? extends GameEvent>, LongAdder> eventCounts = new ConcurrentHashMap<>();

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
        eventCounts.computeIfAbsent(event.getClass(), k -> new LongAdder()).increment();
        List<Consumer<? extends GameEvent>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<? extends GameEvent> listener : list) {
                // rule-exception: {rule:no-casts} — heterogeneous typed-key bag.
                // Listeners are keyed by Class<T> but stored as Consumer<? extends GameEvent>;
                // Java's wildcard system can't express "the T whose Class<T> is this key".
                // The cast is one-per-container at the single dispatch site.
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
