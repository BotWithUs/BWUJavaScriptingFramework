package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.GameEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-script view of a connection's {@link EventBus} that remembers what the
 * script subscribed to, so the runtime can take it all back off again when the
 * script stops.
 *
 * <p>Without this, a stopped script keeps receiving events forever:
 * {@code EventBusImpl} dispatches inline on the event-pump thread and
 * {@link EventBus#unsubscribe} needs the exact {@code Consumer} reference back,
 * which a script that subscribed with a lambda cannot produce. The listener then
 * outlives the script that registered it and can still drive the game.</p>
 *
 * <p>Delegates every call to the shared bus — this only adds bookkeeping, so
 * scripts see identical behaviour while running.</p>
 */
public final class ScopedEventBus implements EventBus {

    private final EventBus delegate;
    /**
     * One undo action per live subscription, captured at {@code subscribe} while
     * {@code T} is still in scope. Storing the closure rather than the
     * {@code (Class, Consumer)} pair is what keeps this class cast-free: a
     * {@code Map<Class<?>, List<Consumer<?>>>} would need an unchecked cast to
     * re-pair a key with its listener, because the wildcard system can't express
     * "the T whose Class&lt;T&gt; is this key".
     */
    private final Map<Consumer<? extends GameEvent>, Runnable> undo = new ConcurrentHashMap<>();

    public ScopedEventBus(EventBus delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T extends GameEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
        undo.put(listener, () -> delegate.unsubscribe(eventType, listener));
        delegate.subscribe(eventType, listener);
    }

    @Override
    public <T extends GameEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        undo.remove(listener);
        delegate.unsubscribe(eventType, listener);
    }

    @Override
    public void publish(GameEvent event) {
        delegate.publish(event);
    }

    /**
     * Removes every subscription this script made. Called from the runner's
     * cleanup, and safe to call more than once.
     */
    public void unsubscribeAll() {
        undo.values().forEach(Runnable::run);
        undo.clear();
    }
}
