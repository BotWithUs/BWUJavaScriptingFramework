package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fluent query builder shared across entity types. Subclasses bind the
 * concrete result type {@code T} (Npc, Player, ...) and provide the
 * snapshot-backed source stream plus the bits that are entity-specific
 * (id field, name resolution).
 *
 * <p>Filters compose: each call narrows the set. Terminal operations
 * ({@link #all()}, {@link #nearest()}, {@link #first()}, {@link #count()},
 * {@link #exists()}, {@link #stream()}) materialise the result. The builder
 * is single-use — once you call a terminal it's done; build a new query
 * for another lookup.</p>
 *
 * <p>All filters run client-side against the published snapshot, so a query
 * is a few microseconds plus whatever definition lookups the rich wrappers
 * trigger (which are cached by id on the entity facade).</p>
 *
 * @param <T> rich entity type
 * @param <Q> self-type for fluent chaining (CRTP)
 */
public abstract class EntityQuery<T extends EntityContext, Q extends EntityQuery<T, Q>> {

    protected final GameAPI api;
    private Predicate<T> filter = t -> true;
    private boolean sortByDistance = false;
    private int limit = Integer.MAX_VALUE;

    protected EntityQuery(GameAPI api) {
        this.api = api;
    }

    @SuppressWarnings("unchecked")
    private Q self() { return (Q) this; }

    // ---------------- Filters ----------------

    /** Adds a predicate; multiple calls AND together. */
    public Q filter(Predicate<T> predicate) {
        this.filter = this.filter.and(predicate);
        return self();
    }

    /** Filter by display name (case-insensitive substring). */
    public Q named(String name) {
        String needle = name.toLowerCase();
        return filter(t -> {
            String n = nameOf(t);
            return n != null && n.toLowerCase().contains(needle);
        });
    }

    /** Filter by display name (case-insensitive exact match). */
    public Q namedExact(String name) {
        return filter(t -> name.equalsIgnoreCase(nameOf(t)));
    }

    /**
     * Filter by display name (regex match against the full name).
     *
     * @throws PatternSyntaxException if {@code regex} is malformed
     */
    public Q nameMatching(String regex) {
        Pattern compiled = Pattern.compile(regex);
        return filter(t -> {
            String n = nameOf(t);
            return n != null && compiled.matcher(n).matches();
        });
    }

    /** Filter by type/definition id. */
    public Q withId(int typeId) {
        return filter(t -> rawTypeId(t) == typeId);
    }

    /** Filter to a specific plane. */
    public Q onPlane(int plane) {
        return filter(t -> t.plane() == plane);
    }

    /**
     * Filter to entities within {@code radius} tiles (Chebyshev) of the
     * given world tile.
     */
    public Q within(int tileX, int tileY, int radius) {
        return filter(t -> t.distanceTo(tileX, tileY) <= radius);
    }

    /**
     * Filter to entities within {@code radius} tiles of the local player.
     * Returns no results when the local player is not in-game.
     */
    public Q withinDistance(int radius) {
        LocalPlayer lp = api.getLocalPlayer();
        if (lp == null) {
            return filter(t -> false);
        }
        int x = lp.tileX(), y = lp.tileY();
        return filter(t -> t.distanceTo(x, y) <= radius);
    }

    /** Sort results by distance from the local player, ascending. */
    public Q sortByDistance() {
        this.sortByDistance = true;
        return self();
    }

    /** Cap the number of results. Applied after sorting. */
    public Q limit(int max) {
        this.limit = Math.max(0, max);
        return self();
    }

    // ---------------- Terminal Operations ----------------

    public List<T> all() {
        Stream<T> stream = source().filter(filter);
        if (sortByDistance) {
            LocalPlayer lp = api.getLocalPlayer();
            if (lp != null) {
                int px = lp.tileX(), py = lp.tileY();
                stream = stream.sorted(Comparator.comparingInt(t -> t.distanceTo(px, py)));
            }
        }
        if (limit < Integer.MAX_VALUE) {
            stream = stream.limit(limit);
        }
        return stream.collect(Collectors.toList());
    }

    /** Nearest result, or {@code null} when no match. Implies {@code sortByDistance()}. */
    public T nearest() {
        sortByDistance();
        List<T> list = all();
        return list.isEmpty() ? null : list.getFirst();
    }

    /** Nearest as Optional. */
    public Optional<T> findNearest() {
        return Optional.ofNullable(nearest());
    }

    /** First match in source order, or {@code null}. No distance sort applied. */
    public T first() {
        List<T> list = all();
        return list.isEmpty() ? null : list.getFirst();
    }

    /** First match as Optional. */
    public Optional<T> findFirst() {
        return Optional.ofNullable(first());
    }

    public int count() { return all().size(); }

    public boolean exists() { return !all().isEmpty(); }

    /** Stream over the materialised result list (filters/sort already applied). */
    public Stream<T> stream() { return all().stream(); }

    // ---------------- Subclass Hooks ----------------

    /** Snapshot-backed source: yield each rich-wrapped entity for this query type. */
    protected abstract Stream<T> source();

    /** Type/definition id from the snapshot record (no defn lookup). */
    protected abstract int rawTypeId(T t);

    /**
     * Display name for {@code named()} / {@code nameMatching()}; resolved
     * via the entity-specific definition cache. May trigger an RPC on first
     * sight of a typeId, then cache for the rest of the session.
     */
    protected abstract String nameOf(T t);
}
