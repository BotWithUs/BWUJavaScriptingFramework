package com.botwithus.bot.api.model;

/** Which entity table a server index belongs to, for the entity reads on {@code GameAPI}. */
public enum EntityKind {
    NPC("npc"),
    PLAYER("player");

    private final String wireName;

    EntityKind(String wireName) {
        this.wireName = wireName;
    }

    /** The agent's {@code targetType} spelling. */
    public String wireName() {
        return wireName;
    }
}
