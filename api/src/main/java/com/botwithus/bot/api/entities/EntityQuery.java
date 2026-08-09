package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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

    private static final Logger log = LoggerFactory.getLogger(EntityQuery.class);

    /** Sentinel for a gameval name that did not resolve. No entity id is negative. */
    private static final int UNRESOLVED = -1;

    protected final GameAPI api;
    private Predicate<T> filter = t -> true;
    private boolean sortByDistance = false;
    private int limit = Integer.MAX_VALUE;

    protected EntityQuery(GameAPI api) {
        this.api = api;
    }

    // rule-exception: {rule:no-casts} — CRTP self-cast. Java cannot express the
    // self-bounded recurrence Q extends EntityQuery<T, Q> at the language level,
    // so the fluent-chain return must narrow once. Isolated to this one helper.
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

    /**
     * Filter by gameval symbolic name — the hook subclasses expose as
     * {@code withGameval(...)} once they know which {@link GamevalType} their
     * type ids live in.
     *
     * <p>Names are resolved once, here, rather than per candidate. A name that
     * does not resolve narrows the query to nothing and logs a warning: a stale
     * or misspelled name must not silently widen the result set to everything.</p>
     *
     * @param type     namespace the subclass's type ids live in
     * @param gamevals one or more names; the filter matches any of them
     */
    protected final Q withGamevalOf(GamevalType type, String... gamevals) {
        if (gamevals.length == 0) {
            log.warn("withGameval() was given no {} names; this query will match nothing",
                    type.wire());
            return filter(t -> false);
        }
        int[] ids = Arrays.stream(gamevals)
                .mapToInt(name -> resolveOrWarn(type, name))
                .filter(id -> id != UNRESOLVED)
                .toArray();
        if (ids.length == 0) {
            return filter(t -> false);
        }
        if (ids.length == 1) {
            return withId(ids[0]);
        }
        return filter(t -> {
            int id = rawTypeId(t);
            for (int candidate : ids) {
                if (candidate == id) {
                    return true;
                }
            }
            return false;
        });
    }

    private int resolveOrWarn(GamevalType type, String gameval) {
        OptionalInt id = api.gamevals().id(type, gameval);
        if (id.isEmpty()) {
            // The index warns once per distinct unknown name; this is the
            // caller-side consequence, so it stays at debug to avoid a second
            // line per tick for the same mistake.
            log.debug("gameval {} '{}' did not resolve; excluded from this query",
                    type.wire(), gameval);
            return UNRESOLVED;
        }
        return id.getAsInt();
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
