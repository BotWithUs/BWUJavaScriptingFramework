package com.botwithus.bot.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An insertion-ordered map that any thread can read without locking.
 *
 * <p>Writers serialise on an internal lock, edit a private copy and publish it
 * through one volatile field. Readers only ever see a complete, immutable
 * snapshot, so iterating it can never throw {@code ConcurrentModificationException}
 * and never observes a half-applied write. Fits small tables that change rarely
 * and are read often, such as the host's connections and groups, which the
 * render thread reads every frame.</p>
 *
 * <p>Keys and values must be non-null.</p>
 */
final class OrderedSnapshotMap<K, V> {

    /** One published state; the list is the map's values, precomputed so readers do not allocate. */
    private record Contents<K, V>(Map<K, V> byKey, List<V> values) {

        static <K, V> Contents<K, V> of(LinkedHashMap<K, V> entries) {
            return new Contents<>(Collections.unmodifiableMap(entries), List.copyOf(entries.values()));
        }
    }

    private final Object writeLock = new Object();
    private volatile Contents<K, V> contents = Contents.of(new LinkedHashMap<>());

    /** Immutable, insertion-ordered view of the current entries. */
    Map<K, V> asMap() {
        return contents.byKey();
    }

    /** Immutable, insertion-ordered list of the current values. */
    List<V> values() {
        return contents.values();
    }

    V get(K key) {
        return contents.byKey().get(key);
    }

    boolean containsKey(K key) {
        return contents.byKey().containsKey(key);
    }

    boolean isEmpty() {
        return contents.values().isEmpty();
    }

    /** The oldest key still present, if any. */
    Optional<K> firstKey() {
        return contents.byKey().keySet().stream().findFirst();
    }

    /** Adds or replaces {@code key}; a replaced key keeps its original position. */
    void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        edit(entries -> entries.put(key, value));
    }

    /** @return {@code true} if {@code key} was absent and is now mapped to {@code value} */
    boolean putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return edit(entries -> entries.putIfAbsent(key, value) == null);
    }

    /** @return the removed value, or {@code null} if {@code key} was absent */
    V remove(K key) {
        return edit(entries -> entries.remove(key));
    }

    /** Removes {@code key} only while it still maps to {@code value}. */
    boolean remove(K key, V value) {
        return edit(entries -> entries.remove(key, value));
    }

    /** Replaces every entry at once, in {@code replacement}'s iteration order. */
    void replaceAll(Map<K, V> replacement) {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>(replacement);
        copy.forEach((key, value) -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        });
        synchronized (writeLock) {
            contents = Contents.of(copy);
        }
    }

    private <R> R edit(Function<LinkedHashMap<K, V>, R> change) {
        synchronized (writeLock) {
            LinkedHashMap<K, V> copy = new LinkedHashMap<>(contents.byKey());
            R result = change.apply(copy);
            contents = Contents.of(copy);
            return result;
        }
    }
}
