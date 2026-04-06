package com.botwithus.bot.api.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable filter for querying world map elements from the game cache.
 *
 * <p>Use the {@link Builder} to construct a filter, then pass it to
 * {@link com.botwithus.bot.api.GameAPI#queryWorldMapElements GameAPI.queryWorldMapElements()}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * WorldMapElementFilter filter = WorldMapElementFilter.builder()
 *     .name("Bank")
 *     .build();
 * List<WorldMapElement> banks = api.queryWorldMapElements(filter);
 * }</pre>
 *
 * @see com.botwithus.bot.api.GameAPI#queryWorldMapElements
 */
public final class WorldMapElementFilter {
    private final Map<String, Object> params;

    private WorldMapElementFilter(Map<String, Object> params) {
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
     * Builder for constructing {@link WorldMapElementFilter} instances.
     */
    public static final class Builder {
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Case-insensitive substring filter on element name.
         *
         * @param name the name substring to match
         * @return this builder
         */
        public Builder name(String name) { params.put("name", name); return this; }

        /**
         * Filters by category ID.
         *
         * @param category the category ID
         * @return this builder
         */
        public Builder category(int category) { params.put("category", category); return this; }

        /**
         * Sets the center tile X for radius filtering.
         *
         * @param centerX the center tile X coordinate
         * @return this builder
         */
        public Builder centerX(int centerX) { params.put("center_x", centerX); return this; }

        /**
         * Sets the center tile Y for radius filtering.
         *
         * @param centerY the center tile Y coordinate
         * @return this builder
         */
        public Builder centerY(int centerY) { params.put("center_y", centerY); return this; }

        /**
         * Sets the tile radius for spatial filtering. A radius of 0 disables spatial filtering.
         *
         * @param radius the tile radius
         * @return this builder
         */
        public Builder radius(int radius) { params.put("radius", radius); return this; }

        /**
         * Filters by plane (height level).
         *
         * @param plane the plane
         * @return this builder
         */
        public Builder plane(int plane) { params.put("plane", plane); return this; }

        /**
         * Limits the maximum number of results. A value of 0 means unlimited.
         *
         * @param maxResults the maximum result count
         * @return this builder
         */
        public Builder maxResults(int maxResults) { params.put("max_results", maxResults); return this; }

        /**
         * Sorts results by distance from the center tile. Requires a radius to be set.
         *
         * @param sort {@code true} to sort by distance
         * @return this builder
         */
        public Builder sortByDistance(boolean sort) { params.put("sort_by_distance", sort); return this; }

        /**
         * Builds the immutable filter.
         *
         * @return a new {@link WorldMapElementFilter}
         */
        public WorldMapElementFilter build() { return new WorldMapElementFilter(params); }
    }
}
