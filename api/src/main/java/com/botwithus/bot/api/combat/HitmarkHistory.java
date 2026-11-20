package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.HitmarkEvent;
import com.botwithus.bot.api.model.EntityKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The recent hitmarks on each entity, collected from {@link HitmarkEvent}s.
 *
 * <pre>{@code
 * HitmarkHistory hits = HitmarkHistory.attach(ctx.getEventBus());
 * boolean beingHit = !hits.recent(EntityKind.PLAYER, self.serverIndex(), 5_000).isEmpty();
 * hits.close();   // in onStop
 * }</pre>
 *
 * <p>It only sees what the event bus delivers: on an agent build that does not emit wire
 * event 70 every list is empty. It keeps at most {@value #PER_ENTITY_CAPACITY} hitmarks per
 * entity and forgets any older than {@value #RETENTION_MS} ms.</p>
 */
public final class HitmarkHistory implements AutoCloseable {

    /** Hitmarks kept per entity. */
    public static final int PER_ENTITY_CAPACITY = 16;
    /** How long a hitmark is kept, in milliseconds. */
    public static final long RETENTION_MS = 30_000L;

    private final Map<Long, Deque<HitmarkEvent>> byEntity = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final EventBus bus;
    private final Consumer<HitmarkEvent> listener = this::record;

    HitmarkHistory(EventBus bus, LongSupplier clock) {
        this.bus = bus;
        this.clock = clock;
    }

    /** Starts collecting from {@code bus}. {@link #close()} stops it. */
    public static HitmarkHistory attach(EventBus bus) {
        HitmarkHistory history = new HitmarkHistory(bus, System::currentTimeMillis);
        bus.subscribe(HitmarkEvent.class, history.listener);
        return history;
    }

    /** The hitmarks on the entity, oldest first. */
    public List<HitmarkEvent> recent(EntityKind kind, int serverIndex) {
        return recent(kind, serverIndex, RETENTION_MS);
    }

    /** The hitmarks on the entity from the last {@code withinMs} milliseconds, oldest first. */
    public List<HitmarkEvent> recent(EntityKind kind, int serverIndex, long withinMs) {
        Deque<HitmarkEvent> deque = byEntity.get(key(kind, serverIndex));
        if (deque == null) {
            return List.of();
        }
        long cutoff = clock.getAsLong() - withinMs;
        synchronized (deque) {
            return deque.stream().filter(hit -> hit.timestamp() >= cutoff).toList();
        }
    }

    /** Total damage on the entity in the last {@code withinMs} milliseconds. */
    public int damageWithin(EntityKind kind, int serverIndex, long withinMs) {
        return recent(kind, serverIndex, withinMs).stream().mapToInt(HitmarkEvent::damage).sum();
    }

    /** Stops collecting and forgets everything. */
    @Override
    public void close() {
        bus.unsubscribe(HitmarkEvent.class, listener);
        byEntity.clear();
    }

    /** Package-private so tests can feed events without a bus. */
    void record(HitmarkEvent hit) {
        EntityKind kind = hit.isOnNpc() ? EntityKind.NPC : EntityKind.PLAYER;
        Deque<HitmarkEvent> deque = byEntity.computeIfAbsent(key(kind, hit.targetServerIndex()),
                k -> new ArrayDeque<>());
        long cutoff = clock.getAsLong() - RETENTION_MS;
        synchronized (deque) {
            deque.addLast(hit);
            while (deque.size() > PER_ENTITY_CAPACITY
                    || (!deque.isEmpty() && deque.peekFirst().timestamp() < cutoff)) {
                deque.removeFirst();
            }
        }
    }

    private static long key(EntityKind kind, int serverIndex) {
        return ((long) kind.ordinal() << Integer.SIZE) | Integer.toUnsignedLong(serverIndex);
    }
}
