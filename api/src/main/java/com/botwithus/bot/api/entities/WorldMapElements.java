package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.query.WorldMapElementFilter;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Query facade for world map elements. Provides convenient methods for finding
 * static map features (banks, altars, dungeons, etc.) from the game cache.
 *
 * <h3>Quick usage:</h3>
 * <pre>{@code
 * WorldMapElements mapElements = new WorldMapElements(api);
 *
 * // Find all banks
 * List<WorldMapElement> banks = mapElements.all("Bank");
 *
 * // Find nearest bank to a position
 * WorldMapElement nearest = mapElements.query()
 *     .named("Bank")
 *     .near(3200, 3200, 100)
 *     .sortByDistance(true)
 *     .nearest();
 *
 * // Find by category
 * List<WorldMapElement> category40 = mapElements.query()
 *     .withCategory(40)
 *     .all();
 * }</pre>
 *
 * @see WorldMapElement
 */
public class WorldMapElements {

    private final GameAPI api;

    public WorldMapElements(GameAPI api) {
        this.api = api;
    }

    /**
     * Start a fluent world map element query.
     */
    public Query query() {
        return new Query(api);
    }

    /**
     * Returns a single world map element by ID, or null if not found.
     */
    public WorldMapElement get(int id) {
        return api.getWorldMapElement(id);
    }

    /**
     * Returns all world map elements matching the given name substring.
     */
    public List<WorldMapElement> all(String name) {
        return query().named(name).all();
    }

    /**
     * Returns the nearest world map element matching the given name to the player.
     */
    public WorldMapElement nearest(String name) {
        return query().named(name).nearPlayer().sortByDistance(true).nearest();
    }

    /**
     * Returns all world map elements with the given category.
     */
    public List<WorldMapElement> allByCategory(int category) {
        return query().withCategory(category).all();
    }

    /**
     * Returns the total number of loaded world map elements.
     */
    public int count() {
        return api.getWorldMapElementCount();
    }

    // ========================== Query ==========================

    /**
     * Fluent query builder for world map elements.
     */
    public static class Query {

        private final GameAPI api;
        private final WorldMapElementFilter.Builder filterBuilder = WorldMapElementFilter.builder();
        private Predicate<WorldMapElement> postFilter;

        Query(GameAPI api) {
            this.api = api;
        }

        /** Filter by name substring (case-insensitive). */
        public Query named(String name) {
            filterBuilder.name(name);
            return this;
        }

        /** Filter by category ID. */
        public Query withCategory(int category) {
            filterBuilder.category(category);
            return this;
        }

        /** Filter to elements near a specific tile within a radius. */
        public Query near(int tileX, int tileY, int radius) {
            filterBuilder.centerX(tileX).centerY(tileY).radius(radius);
            return this;
        }

        /** Filter to elements near the local player within a radius. */
        public Query nearPlayer(int radius) {
            LocalPlayer lp = api.getLocalPlayer();
            filterBuilder.centerX(lp.tileX()).centerY(lp.tileY()).radius(radius);
            return this;
        }

        /** Filter to elements near the local player with a large default radius (500 tiles). */
        public Query nearPlayer() {
            return nearPlayer(500);
        }

        /** Filter by plane (height level). */
        public Query onPlane(int plane) {
            filterBuilder.plane(plane);
            return this;
        }

        /** Sort results by distance from center tile. */
        public Query sortByDistance(boolean sort) {
            filterBuilder.sortByDistance(sort);
            return this;
        }

        /** Limit the maximum number of results. */
        public Query limit(int max) {
            filterBuilder.maxResults(max);
            return this;
        }

        /**
         * Adds a post-query filter predicate applied after elements are returned.
         * Use this for conditions the server-side filter can't express.
         */
        public Query filter(Predicate<WorldMapElement> predicate) {
            this.postFilter = this.postFilter == null ? predicate : this.postFilter.and(predicate);
            return this;
        }

        /** Returns all matching world map elements. */
        public List<WorldMapElement> all() {
            List<WorldMapElement> results = api.queryWorldMapElements(filterBuilder.build());
            if (postFilter != null) {
                results = results.stream().filter(postFilter).toList();
            }
            return results;
        }

        /** Returns the nearest matching element to the center, or null. */
        public WorldMapElement nearest() {
            List<WorldMapElement> results = all();
            return results.isEmpty() ? null : results.getFirst();
        }

        /** Returns the nearest matching element as an {@link Optional}. */
        public Optional<WorldMapElement> findNearest() {
            return Optional.ofNullable(nearest());
        }

        /** Returns the first matching element, or null. */
        public WorldMapElement first() {
            List<WorldMapElement> results = all();
            return results.isEmpty() ? null : results.getFirst();
        }

        /** Returns the first matching element as an {@link Optional}. */
        public Optional<WorldMapElement> findFirst() {
            return Optional.ofNullable(first());
        }

        /** Returns true if at least one matching element exists. */
        public boolean exists() {
            if (postFilter == null) {
                filterBuilder.maxResults(1);
            }
            return !all().isEmpty();
        }

        /** Returns the count of matching elements. */
        public int count() {
            return all().size();
        }
    }
}
