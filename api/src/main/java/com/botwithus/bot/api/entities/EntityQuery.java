package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.query.EntityFilter;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Fluent query builder for entities. Wraps {@link EntityFilter} with a
 * friendlier API and returns rich {@link EntityContext} subclasses.
 *
 * <p>Subclassed by {@link Npcs.Query}, {@link SceneObjects.Query}, and
 * {@link Players.Query} to return the appropriate wrapper type.</p>
 *
 * @param <T> the rich entity wrapper type (e.g., {@link Npc})
 * @param <Q> self-type for fluent chaining
 */
@SuppressWarnings("unchecked")
public abstract class EntityQuery<T extends EntityContext, Q extends EntityQuery<T, Q>> {

    protected final GameAPI api;
    protected final EntityFilter.Builder filterBuilder;
    private Predicate<T> postFilter;

    protected EntityQuery(GameAPI api, String entityType) {
        this.api = api;
        this.filterBuilder = EntityFilter.builder().type(entityType);
    }

    /** Filter by name (contains match, case-insensitive). */
    public Q named(String name) {
        filterBuilder.namePattern(name);
        return self();
    }

    /** Filter by name with exact match. */
    public Q namedExact(String name) {
        filterBuilder.namePattern(name).matchType("exact");
        return self();
    }

    /**
     * Filter by name using a regex pattern.
     *
     * @throws PatternSyntaxException if the regex is invalid
     */
    public Q nameMatching(String regex) {
        // Validate regex to prevent ReDoS and catch errors early
        java.util.regex.Pattern.compile(regex);
        filterBuilder.namePattern(regex).matchType("regex");
        return self();
    }

    /** Filter by type/definition ID. */
    public Q withId(int typeId) {
        filterBuilder.typeId(typeId);
        return self();
    }

    /** Filter by pre-computed name hash. */
    public Q withNameHash(int hash) {
        filterBuilder.nameHash(hash);
        return self();
    }

    /** Only include visible (non-hidden) entities. */
    public Q visible() {
        filterBuilder.visibleOnly(true);
        return self();
    }

    /** Only include entities on the specified plane. */
    public Q onPlane(int plane) {
        filterBuilder.plane(plane);
        return self();
    }

    /** Only include entities within the given radius of a tile. */
    public Q within(int tileX, int tileY, int radius) {
        filterBuilder.tileX(tileX).tileY(tileY).radius(radius);
        return self();
    }

    /** Only include entities within the given radius of the local player. */
    public Q withinDistance(int radius) {
        var lp = api.getLocalPlayer();
        filterBuilder.tileX(lp.tileX()).tileY(lp.tileY()).radius(radius);
        return self();
    }

    /** Only include moving entities. */
    public Q moving() {
        filterBuilder.movingOnly(true);
        return self();
    }

    /** Only include stationary entities. */
    public Q stationary() {
        filterBuilder.stationaryOnly(true);
        return self();
    }

    /** Only include entities in combat. */
    public Q inCombat() {
        filterBuilder.inCombat(true);
        return self();
    }

    /** Only include entities not in combat. */
    public Q notInCombat() {
        filterBuilder.notInCombat(true);
        return self();
    }

    /** Limit the maximum number of results. */
    public Q limit(int max) {
        filterBuilder.maxResults(max);
        return self();
    }

    /**
     * Adds a post-query filter predicate applied after results are wrapped.
     * Use this for conditions the server-side filter can't express
     * (e.g., checking definition fields).
     */
    public Q filter(Predicate<T> predicate) {
        this.postFilter = this.postFilter == null ? predicate : this.postFilter.and(predicate);
        return self();
    }

    // ========================== Terminal Operations ==========================

    /**
     * Returns all matching entities as rich wrappers.
     */
    public List<T> all() {
        List<Entity> raw = api.queryEntities(filterBuilder.build());
        List<T> results = raw.stream().map(wrapFunction()).collect(Collectors.toList());
        if (postFilter != null) {
            results = results.stream().filter(postFilter).collect(Collectors.toList());
        }
        return results;
    }

    /**
     * Returns the nearest matching entity, or null.
     */
    public T nearest() {
        filterBuilder.sortByDistance(true);
        if (postFilter == null) {
            filterBuilder.maxResults(1);
        }
        List<T> results = all();
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Returns the first matching entity (no distance sort), or null.
     */
    public T first() {
        if (postFilter == null) {
            filterBuilder.maxResults(1);
        }
        List<T> results = all();
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Returns the count of matching entities.
     */
    public int count() {
        return all().size();
    }

    /**
     * Returns true if at least one entity matches.
     */
    public boolean exists() {
        if (postFilter == null) {
            filterBuilder.maxResults(1);
        }
        return !all().isEmpty();
    }

    // ========================== Abstract ==========================

    /** Wrapping function that converts a raw Entity to the rich type. */
    protected abstract Function<Entity, T> wrapFunction();

    private Q self() {
        return (Q) this;
    }
}
