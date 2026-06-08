package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ResourceItem;
import com.botwithus.bot.api.model.ResourceSection;
import com.botwithus.bot.api.model.SkillRequirement;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * World-map element query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.mapElements()}.
 *
 * <p>RPC-backed: each terminal operation issues {@code query_world_map_elements}
 * with the accumulated filter map and post-filters / sorts in Java. The
 * producer-side handler is currently a stub returning empty results — once
 * the cache iteration lands, this facade picks up real data without code
 * changes.</p>
 *
 * <pre>{@code
 * WorldMapElement spot = api.mapElements().query()
 *     .withCategory(3032)                  // divination
 *     .withSkill(26, 1, level)             // divination skill range
 *     .withResources()
 *     .nearPlayer()
 *     .sortByDistance()
 *     .nearest();
 * }</pre>
 */
public final class WorldMapElements {

    /** Default tile radius for {@code nearPlayer()} — covers a typical map view. */
    private static final int DEFAULT_QUERY_RADIUS_TILES = 64;

    private final GameAPI api;

    public WorldMapElements(GameAPI api) {
        this.api = api;
    }

    public Query query() {
        return new Query(api);
    }

    /** Convenience: nearest element with the given name (case-insensitive contains). */
    public WorldMapElement nearest(String name) {
        return query().named(name).nearest();
    }

    /** Convenience: all elements with a given name (case-insensitive contains). */
    public List<WorldMapElement> all(String name) {
        return query().named(name).all();
    }

    /** Convenience: all elements in a category. */
    public List<WorldMapElement> allByCategory(int category) {
        return query().withCategory(category).all();
    }

    /**
     * Fluent filter builder for world-map element queries. Producer-side
     * filters (category / skill / name / "with-X" presence flags) ride on
     * the RPC params map; client-side filters (custom predicates, distance
     * sort) apply in Java after the RPC returns.
     */
    public static final class Query {

        private final GameAPI api;
        private final Map<String, Object> rpcFilter = new LinkedHashMap<>();
        private Predicate<WorldMapElement> postFilter = e -> true;
        private boolean sortByDistance = false;
        private int limit = Integer.MAX_VALUE;
        private int sortCenterX = Integer.MIN_VALUE;
        private int sortCenterY = Integer.MIN_VALUE;

        Query(GameAPI api) { this.api = api; }

        // ---------------- Producer-side filters (ride on RPC) ----------------

        public Query named(String name) {
            rpcFilter.put("name", name);
            return this;
        }

        public Query withCategory(int category) {
            rpcFilter.put("category", category);
            return this;
        }

        public Query near(int tileX, int tileY, int radius) {
            rpcFilter.put("tile_x", tileX);
            rpcFilter.put("tile_y", tileY);
            rpcFilter.put("radius", radius);
            sortCenterX = tileX;
            sortCenterY = tileY;
            return this;
        }

        public Query nearPlayer(int radius) {
            LocalPlayer lp = api.getLocalPlayer();
            if (lp == null) {
                // No local player — narrow the result set to nothing producer-side
                // by demanding an unsatisfiable radius around (0,0). The Query
                // result still goes through a real RPC, just with no matches.
                rpcFilter.put("tile_x", 0);
                rpcFilter.put("tile_y", 0);
                rpcFilter.put("radius", 0);
            } else {
                rpcFilter.put("tile_x", lp.tileX());
                rpcFilter.put("tile_y", lp.tileY());
                rpcFilter.put("radius", radius);
                sortCenterX = lp.tileX();
                sortCenterY = lp.tileY();
            }
            return this;
        }

        /** Default radius around the local player (covers a typical map view). */
        public Query nearPlayer() { return nearPlayer(DEFAULT_QUERY_RADIUS_TILES); }

        public Query onPlane(int plane) {
            rpcFilter.put("plane", plane);
            return this;
        }

        public Query withSkill(int skillId) {
            rpcFilter.put("skill_id", skillId);
            return this;
        }

        public Query withSkill(int skillId, int minLevel, int maxLevel) {
            rpcFilter.put("skill_id", skillId);
            rpcFilter.put("min_level", minLevel);
            rpcFilter.put("max_level", maxLevel);
            return this;
        }

        public Query withDescription() {
            rpcFilter.put("with_description", true);
            return this;
        }

        public Query withResources() {
            rpcFilter.put("with_resources", true);
            return this;
        }

        public Query withItemId(int itemId) {
            rpcFilter.put("item_id", itemId);
            return this;
        }

        public Query withResourceNamed(String name) {
            rpcFilter.put("resource_name", name);
            return this;
        }

        // ---------------- Client-side filters / shaping ----------------

        public Query sortByDistance() { this.sortByDistance = true; return this; }
        public Query limit(int max)   { this.limit = Math.max(0, max); return this; }

        public Query filter(Predicate<WorldMapElement> predicate) {
            this.postFilter = this.postFilter.and(predicate);
            return this;
        }

        // ---------------- Terminals ----------------

        public List<WorldMapElement> all() {
            List<WorldMapElement> raw = api.queryWorldMapElements(rpcFilter);
            Stream<WorldMapElement> stream = raw.stream().filter(postFilter);
            if (sortByDistance) {
                int cx = sortCenterX, cy = sortCenterY;
                if (cx == Integer.MIN_VALUE) {
                    LocalPlayer lp = api.getLocalPlayer();
                    if (lp != null) { cx = lp.tileX(); cy = lp.tileY(); }
                }
                if (cx != Integer.MIN_VALUE) {
                    int finalCx = cx, finalCy = cy;
                    stream = stream.sorted(Comparator.comparingInt(
                            e -> chebyshev(e.tileX(), e.tileY(), finalCx, finalCy)));
                }
            }
            if (limit < Integer.MAX_VALUE) {
                stream = stream.limit(limit);
            }
            return stream.collect(Collectors.toList());
        }

        public WorldMapElement nearest() {
            sortByDistance();
            List<WorldMapElement> list = all();
            return list.isEmpty() ? null : list.getFirst();
        }

        public Optional<WorldMapElement> findNearest() { return Optional.ofNullable(nearest()); }

        public WorldMapElement first() {
            List<WorldMapElement> list = all();
            return list.isEmpty() ? null : list.getFirst();
        }

        public Optional<WorldMapElement> findFirst() { return Optional.ofNullable(first()); }

        public boolean exists() { return !all().isEmpty(); }
        public int count()      { return all().size(); }

        private static int chebyshev(int x1, int y1, int x2, int y2) {
            return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
        }
    }
}
