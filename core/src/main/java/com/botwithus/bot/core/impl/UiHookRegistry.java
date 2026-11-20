package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The hook listeners of one connection's agent components, keyed by the agent's
 * handle, and the type of each component, which decides how its events decode (a
 * slider's {@code onselectionchanged} carries a value, a list's an entry).
 *
 * <p>Each listener is stored with the owner that subscribed it, and
 * {@link #listeners} hands out only one owner's: a listener runs as its owner, so a
 * listener some other owner registered must never be run as this one.</p>
 *
 * <p>Only a handle recorded with {@link #recordType} can take a listener, and
 * {@link #forget} drops the handle with every listener on it: a component that is
 * destroyed, released or announced gone takes its subscriptions with it, and the
 * host never sends {@code ui.unsubscribe} for it afterwards.</p>
 *
 * <p>Thread-safe: every method holds the instance lock, and none calls out while
 * holding it.</p>
 */
final class UiHookRegistry {

    /** One subscribed listener and the owner it runs as. */
    private record Subscriber(String owner, Consumer<UiHookEvent> listener) {
    }

    private record Entry(UiType type, Map<UiHook, List<Subscriber>> listeners) {
    }

    private final Map<Long, Entry> entries = new HashMap<>();

    /** Records an agent component the binding created, so it can take listeners. */
    synchronized void recordType(long handle, UiType type) {
        entries.put(handle, new Entry(type, new EnumMap<>(UiHook.class)));
    }

    /** The recorded type; empty for a handle that was never recorded or is forgotten. */
    synchronized Optional<UiType> typeOf(long handle) {
        Entry entry = entries.get(handle);
        return entry != null ? Optional.of(entry.type()) : Optional.empty();
    }

    /** Whether {@code hook} has a listener on {@code handle}. */
    synchronized boolean hasListeners(long handle, UiHook hook) {
        Entry entry = entries.get(handle);
        return entry != null && !entry.listeners().getOrDefault(hook, List.of()).isEmpty();
    }

    /** Adds a listener; {@code false}, adding nothing, when the handle is not (or no longer) recorded. */
    synchronized boolean add(long handle, UiHook hook, String owner, Consumer<UiHookEvent> listener) {
        Entry entry = entries.get(handle);
        if (entry == null) {
            return false;
        }
        entry.listeners().computeIfAbsent(hook, h -> new ArrayList<>()).add(new Subscriber(owner, listener));
        return true;
    }

    /**
     * Removes one listener. Returns whether it was the hook's last one, so the hook is
     * now unsubscribed; {@code false} when it was not registered (already removed, or
     * its handle forgotten).
     */
    synchronized boolean remove(long handle, UiHook hook, Consumer<UiHookEvent> listener) {
        Entry entry = entries.get(handle);
        if (entry == null) {
            return false;
        }
        List<Subscriber> registered = entry.listeners().get(hook);
        if (registered == null || !registered.removeIf(s -> s.listener() == listener)) {
            return false;
        }
        if (registered.isEmpty()) {
            entry.listeners().remove(hook);
            return true;
        }
        return false;
    }

    /** Removes every listener of {@code hook} on {@code handle}. */
    synchronized void removeAll(long handle, UiHook hook) {
        Entry entry = entries.get(handle);
        if (entry != null) {
            entry.listeners().remove(hook);
        }
    }

    /** A copy of the listeners {@code owner} subscribed for the hook, read when an event is delivered. */
    synchronized List<Consumer<UiHookEvent>> listeners(long handle, UiHook hook, String owner) {
        Entry entry = entries.get(handle);
        if (entry == null) {
            return List.of();
        }
        return entry.listeners().getOrDefault(hook, List.of()).stream()
                .filter(s -> s.owner().equals(owner))
                .map(Subscriber::listener)
                .toList();
    }

    /** Drops the handle and its listeners. */
    synchronized void forget(long handle) {
        entries.remove(handle);
    }

    /** Drops everything, for a connection that was replaced. */
    synchronized void clear() {
        entries.clear();
    }

    /** How many handles are recorded. */
    synchronized int size() {
        return entries.size();
    }
}
