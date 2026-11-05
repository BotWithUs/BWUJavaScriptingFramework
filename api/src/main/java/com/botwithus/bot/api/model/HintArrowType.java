package com.botwithus.bot.api.model;

/** What a {@link HintArrow} points at, as the agent's {@code type} code names it. */
public enum HintArrowType {
    NPC(1),
    TILE_CENTRE(2),
    TILE_WEST(3),
    TILE_EAST(4),
    TILE_SOUTH(5),
    TILE_NORTH(6),
    TILE_CORNER(7),
    PLAYER(10),
    /** A code this host does not list; {@link HintArrow#rawType()} still carries it. */
    UNKNOWN(-1);

    private final int wireCode;

    HintArrowType(int wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's {@code type} code; {@code -1} for {@link #UNKNOWN}. */
    public int wireCode() {
        return wireCode;
    }

    /** True for the six kinds that point at a tile rather than an entity. */
    public boolean isTile() {
        return this != NPC && this != PLAYER && this != UNKNOWN;
    }

    /** The type for an agent {@code type} code; {@link #UNKNOWN} for anything unlisted. */
    public static HintArrowType fromWireCode(int code) {
        for (HintArrowType type : values()) {
            if (type != UNKNOWN && type.wireCode == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
