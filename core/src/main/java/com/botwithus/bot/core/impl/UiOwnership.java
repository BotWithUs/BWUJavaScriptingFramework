package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiSubscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Which owner (script, management script or the host) on a connection made which
 * plugin-UI handle, which owners are closed, and who listens.
 *
 * <p>The agent owns handles per pipe connection, and every script on a client
 * shares one connection, so this is the only place per-script ownership exists.
 * Handles here are the <b>agent's</b> numbers; the binding maps them to the
 * epoch-carrying numbers scripts see. Two kinds are tracked differently:</p>
 * <ul>
 *   <li><b>Agent handles</b> ({@code ui.create}) have exactly one owner, and that
 *       owner's release destroys them.</li>
 *   <li><b>Jagex handles</b> ({@code ui.get_component}) are deduplicated by the
 *       agent per connection, so two scripts looking up one component share a
 *       handle. They cannot be destroyed; the set of owners that looked one up
 *       decides only who hears about it.</li>
 * </ul>
 *
 * <p><b>Closed owners.</b> {@link #close} marks an owner as released before its
 * handles are destroyed, and {@link #recordCreated} refuses to record for a closed
 * owner. The check and the record happen under one lock, so a create whose reply
 * lands during a release is either in the release's list or refused, never both
 * missed. A run that starts again calls {@link #reopen}.</p>
 *
 * <p>Thread-safe: every method holds the instance lock, and none calls out while
 * holding it.</p>
 */
final class UiOwnership {

    /** Agent handle → owner, in creation order (release destroys newest first). */
    private final Map<Long, String> agentOwners = new LinkedHashMap<>();
    private final Map<Long, Set<String>> jagexOwners = new HashMap<>();
    private final Map<String, List<Consumer<UiEvent>>> listeners = new HashMap<>();
    private final Set<String> closed = new HashSet<>();

    /** Records a created handle; {@code false}, recording nothing, when the owner is closed. */
    synchronized boolean recordCreated(String owner, long handle) {
        if (closed.contains(owner)) {
            return false;
        }
        agentOwners.put(handle, owner);
        return true;
    }

    /** Records a Jagex lookup; a closed owner's lookup is not recorded (it needs no cleanup). */
    synchronized void recordLookup(String owner, long handle) {
        if (!closed.contains(owner)) {
            jagexOwners.computeIfAbsent(handle, h -> new LinkedHashSet<>()).add(owner);
        }
    }

    /** The owner of an agent handle; {@code null} when this connection's owners did not make it. */
    synchronized String agentOwnerOf(long handle) {
        return agentOwners.get(handle);
    }

    /** Whether {@code handle} is one of {@code owner}'s own. */
    synchronized boolean isOwnedBy(String owner, long handle, boolean isJagex) {
        if (isJagex) {
            return jagexOwners.getOrDefault(handle, Set.of()).contains(owner);
        }
        return owner.equals(agentOwners.get(handle));
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
     * Closes {@code owner}: refuses its later creates, drops its listeners and its
     * share of Jagex handles, and returns its agent handles newest first. They stay
     * tracked until the caller has tried to destroy each one and calls
     * {@link #forget}. Safe to call more than once.
     */
    synchronized List<Long> close(String owner) {
        closed.add(owner);
        listeners.remove(owner);
        jagexOwners.values().forEach(owners -> owners.remove(owner));
        jagexOwners.values().removeIf(Set::isEmpty);
        return agentHandlesOf(owner);
    }

    /** Lets a closed owner create again: a new run of the same script. */
    synchronized void reopen(String owner) {
        closed.remove(owner);
    }

    synchronized boolean isClosed(String owner) {
        return closed.contains(owner);
    }

    /**
     * Forgets every handle, for a connection that was replaced: the agent destroyed
     * all of them with the old pipe. Returns, per owner, the handles it held (agent
     * and Jagex) so each can be told. Listeners and closed marks are kept.
     */
    synchronized Map<String, List<Long>> reset() {
        Map<String, List<Long>> lost = new LinkedHashMap<>();
        agentOwners.forEach((handle, owner) -> lost.computeIfAbsent(owner, o -> new ArrayList<>()).add(handle));
        jagexOwners.forEach((handle, owners) -> owners.forEach(
                owner -> lost.computeIfAbsent(owner, o -> new ArrayList<>()).add(handle)));
        agentOwners.clear();
        jagexOwners.clear();
        return lost;
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

    /** A copy of {@code owner}'s listeners, read when an event is delivered. */
    synchronized List<Consumer<UiEvent>> listenersOf(String owner) {
        return List.copyOf(listeners.getOrDefault(owner, List.of()));
    }

    /**
     * Who hears an event about agent handle {@code handle}: the owner that made it,
     * or every owner that looked a Jagex handle up, among those with a listener.
     * Nobody else, so one script never sees another's handles. A {@code destroyed}
     * event also stops tracking the handle, since there is nothing left to destroy.
     */
    synchronized List<String> route(long handle, boolean isDestroyed) {
        Set<String> owners = new LinkedHashSet<>(jagexOwners.getOrDefault(handle, Set.of()));
        String agentOwner = agentOwners.get(handle);
        if (agentOwner != null) {
            owners.add(agentOwner);
        }
        if (isDestroyed) {
            forget(handle);
        }
        return owners.stream().filter(listeners::containsKey).toList();
    }
}
