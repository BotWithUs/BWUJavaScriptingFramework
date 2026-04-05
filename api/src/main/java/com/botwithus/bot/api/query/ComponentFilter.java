package com.botwithus.bot.api.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable filter for querying interface components.
 *
 * <p>Use the {@link Builder} to construct a filter, then pass it to
 * {@link com.botwithus.bot.api.GameAPI#queryComponents GameAPI.queryComponents()}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * ComponentFilter filter = ComponentFilter.builder()
 *     .interfaceId(1473)
 *     .itemId(1234)
 *     .visibleOnly(true)
 *     .build();
 * List<Component> comps = api.queryComponents(filter);
 * }</pre>
 *
 * @see com.botwithus.bot.api.GameAPI#queryComponents
 */
public final class ComponentFilter {
    private final Map<String, Object> params;

    private ComponentFilter(Map<String, Object> params) {
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
     * Builder for constructing {@link ComponentFilter} instances.
     */
    public static final class Builder {
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Filters by parent interface ID.
         *
         * @param id the interface ID
         * @return this builder
         */
        public Builder interfaceId(int id) { params.put("interface_id", id); return this; }

        /**
         * Filters by the item ID displayed in the component.
         *
         * @param id the item ID
         * @return this builder
         */
        public Builder itemId(int id) { params.put("item_id", id); return this; }

        /**
         * Filters by the sprite ID displayed in the component.
         *
         * @param id the sprite ID
         * @return this builder
         */
        public Builder spriteId(int id) { params.put("sprite_id", id); return this; }

        /**
         * Filters by component type.
         *
         * @param type the component type ID
         * @return this builder
         */
        public Builder type(int type) { params.put("type", type); return this; }

        /**
         * Filters by text content pattern.
         *
         * @param pattern the text pattern to match
         * @return this builder
         */
        public Builder textPattern(String pattern) { params.put("text_pattern", pattern); return this; }

        /**
         * Sets the matching mode for text patterns (e.g., {@code "exact"}, {@code "contains"}, {@code "regex"}).
         *
         * @param matchType the match type
         * @return this builder
         */
        public Builder matchType(String matchType) { params.put("match_type", matchType); return this; }

        /**
         * Sets the matching mode for text patterns using the type-safe enum.
         *
         * @param matchType the match type
         * @return this builder
         * @see MatchType
         */
        public Builder matchType(MatchType matchType) { params.put("match_type", matchType.wireValue()); return this; }

        /**
         * Sets whether text matching is case-sensitive.
         *
         * @param cs {@code true} for case-sensitive matching
         * @return this builder
         */
        public Builder caseSensitive(boolean cs) { params.put("case_sensitive", cs); return this; }

        /**
         * Filters by right-click option pattern.
         *
         * @param pattern the option pattern to match
         * @return this builder
         */
        public Builder optionPattern(String pattern) { params.put("option_pattern", pattern); return this; }

        /**
         * Sets the matching mode for option patterns.
         *
         * @param matchType the match type (e.g., {@code "exact"}, {@code "contains"})
         * @return this builder
         */
        public Builder optionMatchType(String matchType) { params.put("option_match_type", matchType); return this; }

        /**
         * Sets the matching mode for option patterns using the type-safe enum.
         *
         * @param matchType the match type
         * @return this builder
         * @see MatchType
         */
        public Builder optionMatchType(MatchType matchType) { params.put("option_match_type", matchType.wireValue()); return this; }

        /**
         * If {@code true}, only returns components that are currently visible.
         *
         * @param v {@code true} to filter to visible components only
         * @return this builder
         */
        public Builder visibleOnly(boolean v) { params.put("visible_only", v); return this; }

        /**
         * Limits the maximum number of results returned.
         *
         * @param max the maximum result count
         * @return this builder
         */
        public Builder maxResults(int max) { params.put("max_results", max); return this; }

        /**
         * Builds the immutable {@link ComponentFilter}.
         *
         * @return the constructed filter
         */
        public ComponentFilter build() { return new ComponentFilter(params); }
    }
}
