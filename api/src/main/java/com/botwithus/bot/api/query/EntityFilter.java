package com.botwithus.bot.api.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable filter for querying game entities (NPCs, players, objects).
 *
 * <p>Use the {@link Builder} to construct a filter, then pass it to
 * {@link com.botwithus.bot.api.GameAPI#queryEntities GameAPI.queryEntities()}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * EntityFilter filter = EntityFilter.builder()
 *     .namePattern("Goblin")
 *     .visibleOnly(true)
 *     .sortByDistance(true)
 *     .maxResults(5)
 *     .build();
 * List<Entity> goblins = api.queryEntities(filter);
 * }</pre>
 *
 * @see com.botwithus.bot.api.GameAPI#queryEntities
 */
public final class EntityFilter {
    private final Map<String, Object> params;

    private EntityFilter(Map<String, Object> params) {
        this.params = Map.copyOf(params);
    }

    /**
     * Returns the filter parameters as an unmodifiable map for RPC serialization.
     *
     * @return the filter parameter map
     */
    public Map<String, Object> toParams() { return params; }

    /**
     * Creates a new filter builder.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Builder for constructing {@link EntityFilter} instances.
     */
    public static final class Builder {
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Filters by entity type (e.g., {@code "npc"}, {@code "player"}, {@code "object"}).
         *
         * @param type the entity type string
         * @return this builder
         */
        public Builder type(String type) { params.put("type", type); return this; }

        /**
         * Filters by entity type using the type-safe enum.
         *
         * @param type the entity type
         * @return this builder
         * @see EntityType
         */
        public Builder type(EntityType type) { params.put("type", type.wireValue()); return this; }

        /**
         * Filters by entity type ID (e.g., NPC definition ID).
         *
         * @param id the type ID
         * @return this builder
         */
        public Builder typeId(int id) { params.put("type_id", id); return this; }

        /**
         * Filters by pre-computed name hash.
         *
         * @param hash the name hash
         * @return this builder
         * @see com.botwithus.bot.api.GameAPI#computeNameHash
         */
        public Builder nameHash(int hash) { params.put("name_hash", hash); return this; }

        /**
         * Filters by name pattern (supports wildcards depending on {@link #matchType}).
         *
         * @param pattern the name pattern to match
         * @return this builder
         */
        public Builder namePattern(String pattern) { params.put("name_pattern", pattern); return this; }

        /**
         * Sets the matching mode for name patterns (e.g., {@code "exact"}, {@code "contains"}, {@code "regex"}).
         *
         * @param matchType the match type
         * @return this builder
         */
        public Builder matchType(String matchType) { params.put("match_type", matchType); return this; }

        /**
         * Sets the matching mode for name patterns using the type-safe enum.
         *
         * @param matchType the match type
         * @return this builder
         * @see MatchType
         */
        public Builder matchType(MatchType matchType) { params.put("match_type", matchType.wireValue()); return this; }

        /**
         * Sets whether name matching is case-sensitive.
         *
         * @param cs {@code true} for case-sensitive matching
         * @return this builder
         */
        public Builder caseSensitive(boolean cs) { params.put("case_sensitive", cs); return this; }

        /**
         * Filters by plane (height level).
         *
         * @param plane the plane number (0-3)
         * @return this builder
         */
        public Builder plane(int plane) { params.put("plane", plane); return this; }

        /**
         * Filters by tile X coordinate (center of search area when used with {@link #radius}).
         *
         * @param x the tile X coordinate
         * @return this builder
         */
        public Builder tileX(int x) { params.put("tile_x", x); return this; }

        /**
         * Filters by tile Y coordinate (center of search area when used with {@link #radius}).
         *
         * @param y the tile Y coordinate
         * @return this builder
         */
        public Builder tileY(int y) { params.put("tile_y", y); return this; }

        /**
         * Sets the search radius around the specified tile coordinates.
         *
         * @param radius the search radius in tiles
         * @return this builder
         */
        public Builder radius(int radius) { params.put("radius", radius); return this; }

        /**
         * If {@code true}, only returns entities that are currently visible (not hidden).
         *
         * @param v {@code true} to filter to visible entities only
         * @return this builder
         */
        public Builder visibleOnly(boolean v) { params.put("visible_only", v); return this; }

        /**
         * If {@code true}, only returns entities that are currently moving.
         *
         * @param v {@code true} to filter to moving entities only
         * @return this builder
         */
        public Builder movingOnly(boolean v) { params.put("moving_only", v); return this; }

        /**
         * If {@code true}, only returns entities that are stationary (not moving).
         *
         * @param v {@code true} to filter to stationary entities only
         * @return this builder
         */
        public Builder stationaryOnly(boolean v) { params.put("stationary_only", v); return this; }

        /**
         * If {@code true}, only returns entities that are currently in combat.
         *
         * @param v {@code true} to filter to entities in combat
         * @return this builder
         */
        public Builder inCombat(boolean v) { params.put("in_combat", v); return this; }

        /**
         * If {@code true}, only returns entities that are not in combat.
         *
         * @param v {@code true} to filter to entities not in combat
         * @return this builder
         */
        public Builder notInCombat(boolean v) { params.put("not_in_combat", v); return this; }

        /**
         * If {@code true}, results are sorted by distance from the local player (nearest first).
         *
         * @param v {@code true} to sort by distance
         * @return this builder
         */
        public Builder sortByDistance(boolean v) { params.put("sort_by_distance", v); return this; }

        /**
         * Limits the maximum number of results returned.
         *
         * @param max the maximum result count
         * @return this builder
         */
        public Builder maxResults(int max) { params.put("max_results", max); return this; }

        /**
         * Builds the immutable {@link EntityFilter}.
         *
         * @return the constructed filter
         */
        public EntityFilter build() { return new EntityFilter(params); }
    }
}
