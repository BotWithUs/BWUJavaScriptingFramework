package com.botwithus.bot.api.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable filter for enumerating world map icon placements from cache
 * archive 41. Unlike {@link WorldMapElementFilter}, this returns one entry
 * per placement (so an element with many map locations yields many results).
 *
 * <p>Use the {@link Builder} to construct a filter, then pass it to
 * {@link com.botwithus.bot.api.GameAPI#queryWorldMapIcons GameAPI.queryWorldMapIcons()}.</p>
 *
 * <p>Example: find every bank icon within 200 tiles of a point.</p>
 * <pre>{@code
 * WorldMapIconFilter filter = WorldMapIconFilter.builder()
 *     .spriteId(BANK_SPRITE_ID)
 *     .near(3200, 3200, 200)
 *     .sortByDistance(true)
 *     .build();
 * List<WorldMapIconResult> icons = api.queryWorldMapIcons(filter);
 * }</pre>
 *
 * @see com.botwithus.bot.api.GameAPI#queryWorldMapIcons
 */
public final class WorldMapIconFilter {
    private final Map<String, Object> params;

    private WorldMapIconFilter(Map<String, Object> params) {
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
     * Builder for constructing {@link WorldMapIconFilter} instances.
     */
    public static final class Builder {
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

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
         * Sets the tile radius for spatial filtering. A radius of 0 disables
         * spatial filtering and returns icons from every world region.
         *
         * @param radius the tile radius
         * @return this builder
         */
        public Builder radius(int radius) { params.put("radius", radius); return this; }

        /**
         * Convenience helper equivalent to
         * {@link #centerX(int)}/{@link #centerY(int)}/{@link #radius(int)}.
         *
         * @param centerX the center tile X coordinate
         * @param centerY the center tile Y coordinate
         * @param radius  the tile radius
         * @return this builder
         */
        public Builder near(int centerX, int centerY, int radius) {
            params.put("center_x", centerX);
            params.put("center_y", centerY);
            params.put("radius", radius);
            return this;
        }

        /**
         * Filters by plane (height level).
         *
         * @param plane the plane
         * @return this builder
         */
        public Builder plane(int plane) { params.put("plane", plane); return this; }

        /**
         * Filters icons by their sprite id. Every placement sharing the same
         * sprite (e.g., all banks, all altars) maps to a single sprite id.
         *
         * @param spriteId the icon sprite id
         * @return this builder
         */
        public Builder spriteId(int spriteId) { params.put("sprite_id", spriteId); return this; }

        /**
         * Filters icons by element category id.
         *
         * @param category the category id
         * @return this builder
         */
        public Builder category(int category) { params.put("category", category); return this; }

        /**
         * Restricts results to placements of a single world map element.
         *
         * @param worldMapElementId the owning element id
         * @return this builder
         */
        public Builder worldMapElementId(int worldMapElementId) {
            params.put("world_map_element_id", worldMapElementId);
            return this;
        }

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
         * Controls whether the server populates sprite/category/name/tooltip
         * fields from the owning element. Defaults to {@code true}; set to
         * {@code false} for a lighter payload when only coordinates matter.
         *
         * @param enrich {@code true} to enrich with element fields
         * @return this builder
         */
        public Builder enrich(boolean enrich) { params.put("enrich", enrich); return this; }

        /**
         * Controls whether members-only placements are included. Defaults to
         * {@code true}.
         *
         * @param include {@code true} to include members-only placements
         * @return this builder
         */
        public Builder includeMembers(boolean include) { params.put("include_members", include); return this; }

        /**
         * Builds the immutable filter.
         *
         * @return a new {@link WorldMapIconFilter}
         */
        public WorldMapIconFilter build() { return new WorldMapIconFilter(params); }
    }
}
