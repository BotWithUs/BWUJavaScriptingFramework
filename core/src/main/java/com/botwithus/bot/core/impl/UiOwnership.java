package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiSubscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Which script on a connection made which plugin-UI handle, and who listens.
 *
 * <p>The agent owns handles per pipe connection, and every script on a client
 * shares one connection, so this is the only place per-script ownership exists.
 * Two kinds of handle are tracked differently:</p>
 * <ul>
 *   <li><b>Agent handles</b> ({@code ui.create}) have exactly one owner, and that
 *       owner's stop destroys them.</li>
 *   <li><b>Jagex handles</b> ({@code ui.get_component}) are deduplicated by the
 *       agent per connection, so two scripts looking up one component share a
 *       handle. They cannot be destroyed; the set of scripts that looked one up
 *       decides only who hears about it.</li>
 * </ul>
 *
 * <p>Thread-safe: every method holds the instance lock, and none calls out while
 * holding it.</p>
 */
final class UiOwnership {

    /** One listener to call for an event, and the script it belongs to. */
    record Delivery(String owner, Consumer<UiEvent> listener) {
    }

    /** Agent handle → owner, in creation order (release destroys newest first). */
    private final Map<Long, String> agentOwners = new LinkedHashMap<>();
    private final Map<Long, Set<String>> jagexOwners = new HashMap<>();
    private final Map<String, List<Consumer<UiEvent>>> listeners = new HashMap<>();

    synchronized void recordCreated(String owner, long handle) {
        agentOwners.put(handle, owner);
    }

    synchronized void recordLookup(String owner, long handle) {
        jagexOwners.computeIfAbsent(handle, h -> new LinkedHashSet<>()).add(owner);
    }

    /** The script that made an agent handle; {@code null} when this connection's scripts did not. */
    synchronized String agentOwnerOf(long handle) {
        return agentOwners.get(handle);
    }

    /** Whether {@code info} is one of {@code owner}'s own handles. */
    synchronized boolean isOwnedBy(String owner, UiHandleInfo info) {
        if (info.isJagex()) {
            return jagexOwners.getOrDefault(info.handle(), Set.of()).contains(owner);
        }
        return owner.equals(agentOwners.get(info.handle()));
    }

    /** {@code owner}'s agent handles, newest first, so children go before their parents. */
    synchronized List<Long> agentHandlesOf(String owner) {
        List<Long> owned = new ArrayList<>();
        agentOwners.forEach((handle, o) -> {
            if (o.equals(owner)) {
                owned.add(handle);
            }
        });
        Collections.reverse(owned);
        return owned;
    }

    synchronized void forget(long handle) {
        agentOwners.remove(handle);
        jagexOwners.remove(handle);
    }

    /**
     * Drops everything {@code owner} holds: its listeners, its share of Jagex
     * handles, and its agent handles, which are returned newest first for the
     * caller to destroy. Safe to call more than once.
     */
    synchronized List<Long> release(String owner) {
        List<Long> owned = agentHandlesOf(owner);
        owned.forEach(agentOwners::remove);
        jagexOwners.values().forEach(owners -> owners.remove(owner));
        jagexOwners.values().removeIf(Set::isEmpty);
        listeners.remove(owner);
        return owned;
    }

    synchronized UiSubscription addListener(String owner, Consumer<UiEvent> listener) {
        listeners.computeIfAbsent(owner, o -> new ArrayList<>()).add(listener);
        return () -> removeListener(owner, listener);
    }

    private synchronized void removeListener(String owner, Consumer<UiEvent> listener) {
        List<Consumer<UiEvent>> registered = listeners.get(owner);
        if (registered != null) {
            registered.remove(listener);
        }
    }

    /**
     * Who hears {@code event}: the listeners of the script that made the handle,
     * or of every script that looked a Jagex handle up. Nobody else, so one
     * script never sees another's handles. A {@code destroyed} event also stops
     * tracking the handle, since it no longer exists to destroy.
     */
    synchronized List<Delivery> route(UiEvent event) {
        long handle = event.handle();
        Set<String> owners = new LinkedHashSet<>(jagexOwners.getOrDefault(handle, Set.of()));
        String agentOwner = agentOwners.get(handle);
        if (agentOwner != null) {
            owners.add(agentOwner);
        }
        if (event.isDestroyed()) {
            forget(handle);
        }
        List<Delivery> deliveries = new ArrayList<>();
        for (String owner : owners) {
            listeners.getOrDefault(owner, List.of())
                    .forEach(l -> deliveries.add(new Delivery(owner, l)));
        }
        return deliveries;
    }
}
