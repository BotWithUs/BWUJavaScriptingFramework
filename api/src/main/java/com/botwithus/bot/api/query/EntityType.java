package com.botwithus.bot.api.query;

/**
 * Type-safe enum for entity types used in {@link EntityFilter} queries.
 *
 * @see EntityFilter.Builder#type(EntityType)
 */
public enum EntityType {

    /** Non-player characters. */
    NPC("npc"),

    /** Other players in the game world. */
    PLAYER("player"),

    /** Scene objects (game objects / locations). */
    LOCATION("location");

    private final String wireValue;

    EntityType(String wireValue) {
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
