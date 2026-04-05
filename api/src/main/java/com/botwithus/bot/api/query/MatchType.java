package com.botwithus.bot.api.query;

/**
 * Type-safe enum for name matching modes used in {@link EntityFilter}
 * and {@link ComponentFilter} queries.
 *
 * @see EntityFilter.Builder#matchType(MatchType)
 * @see ComponentFilter.Builder#optionMatchType(MatchType)
 */
public enum MatchType {

    /** Substring match (case-insensitive by default). */
    CONTAINS("contains"),

    /** Exact string match. */
    EXACT("exact"),

    /** Regular expression match. */
    REGEX("regex");

    private final String wireValue;

    MatchType(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the string value sent over the RPC wire.
     *
     * @return the wire protocol value
     */
    public String wireValue() {
        return wireValue;
    }
}
