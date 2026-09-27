package com.botwithus.bot.cli.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The most recent {@link HostEvent}s, per client and for the host as a whole.
 *
 * <p>Subscribe it to a {@link HostEventBus}. Each client keeps its own bounded
 * history, so a noisy client can only ever evict its own past; host-wide events
 * (load failures, management scripts) keep a separate one of the same bound.
 * History outlives the connection it describes, so a closed client's timeline
 * stays readable until the client is forgotten.</p>
 *
 * <p>Thread-safe. Every read returns an immutable snapshot that later events
 * never change, so the render thread can iterate one freely.</p>
 */
public final class ConnectionHistory implements Consumer<HostEvent> {

    /** Events kept per client, and host-wide, unless a capacity is given. */
    public static final int DEFAULT_CAPACITY = 200;

    /** An event with its arrival number, which orders the merged view. */
    private record Entry(long seq, HostEvent event) { }

    private final int capacity;
    private final Object lock = new Object();
    /** Per client, in the order clients were first seen. Guarded by {@link #lock}. */
    private final Map<String, Deque<Entry>> byClient = new LinkedHashMap<>();
    /** Guarded by {@link #lock}. */
    private final Deque<Entry> hostWide = new ArrayDeque<>();
    /** Guarded by {@link #lock}. */
    private long nextSeq;

    public ConnectionHistory() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity events kept per client and host-wide; the oldest go first
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public ConnectionHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Records {@code event}, evicting the oldest event of the same history if it
     * is full. A {@link HostEvent.ClientForgotten} drops that client's history
     * and is itself recorded host-wide, so the forget stays visible.
     */
    @Override
    public void accept(HostEvent event) {
        synchronized (lock) {
            Deque<Entry> history = switch (event) {
                case HostEvent.ClientForgotten forgotten -> {
                    byClient.remove(forgotten.client().pipe());
                    yield hostWide;
                }
                case HostEvent.ClientEvent c -> byClient.computeIfAbsent(
                        c.client().pipe(), pipe -> new ArrayDeque<>());
                case HostEvent.ScriptLoadFailed _, HostEvent.ManagementAction _,
                     HostEvent.ManagementScriptCrashed _ -> hostWide;
            };
            if (history.size() == capacity) {
                history.removeFirst();
            }
            history.addLast(new Entry(nextSeq++, event));
        }
    }

    /** A client's recorded events, oldest first. Empty for a client never seen. */
    public List<HostEvent> forClient(String pipe) {
        synchronized (lock) {
            Deque<Entry> history = byClient.get(pipe);
            return history == null ? List.of() : eventsOf(history);
        }
    }

    /** Events about the host as a whole rather than one client, oldest first. */
    public List<HostEvent> hostWide() {
        synchronized (lock) {
            return eventsOf(hostWide);
        }
    }

    /**
     * Every retained event, per client and host-wide, in the order they were
     * published. Each history is bounded on its own, so this holds whatever each
     * of them still has, not simply the latest N events overall.
     */
    public List<HostEvent> merged() {
        List<Entry> all = new ArrayList<>();
        synchronized (lock) {
            byClient.values().forEach(all::addAll);
            all.addAll(hostWide);
        }
        all.sort(Comparator.comparingLong(Entry::seq));
        return all.stream().map(Entry::event).toList();
    }

    /** Every client with a history, in the order each was first seen. */
    public List<String> clients() {
        synchronized (lock) {
            return List.copyOf(byClient.keySet());
        }
    }

    private static List<HostEvent> eventsOf(Deque<Entry> history) {
        return history.stream().map(Entry::event).toList();
    }
}
