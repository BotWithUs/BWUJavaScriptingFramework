package com.botwithus.bot.api.draw;

/**
 * Which list a highlighted entity's index belongs to.
 *
 * <p>{@code highlight_entity} takes exactly one of {@code npc}, {@code player} or
 * {@code self}: two is an error and none is an error, because "which one wins" is
 * not a rule a caller could guess from a reply that succeeded. An index alone is
 * ambiguous — npc 42 and player 42 are different entities in different lists — so
 * the reference is the pair, and this enum is the half that says which list.</p>
 *
 * <p><b>{@link #SELF} carries no index</b>, deliberately. The local player's list
 * slot is re-resolved by the producer every tick, so a stored slot would point at
 * whoever took the seat after a world hop. {@link #hasIndex()} is what the encoder
 * and the auto-key formatter both branch on rather than each spelling the rule
 * again.</p>
 *
 * <p><b>One name, two jobs, and that is true on the wire rather than a shortcut
 * taken here.</b> {@link #wireName()} is both the parameter the producer reads the
 * reference from and the prefix of the key it generates when the caller names
 * none — {@code npc:42}, {@code player:7}, {@code self}. Mirrors
 * {@code overlay::EntityKind} in {@code NXTLibrary/src/overlay/DrawTypes.h} and the
 * prefix {@code Handle_HighlightEntity} formats.</p>
 */
public enum EntityRef {

    /** An NPC, by its server-side NPC slot. */
    NPC("npc", true),

    /** Another player, by its server-side player slot. */
    PLAYER("player", true),

    /**
     * The local player, with no index at all — the producer re-resolves the slot
     * every tick so the highlight survives a world hop.
     */
    SELF("self", false);

    private final String wireName;
    private final boolean hasIndex;

    EntityRef(String wireName, boolean hasIndex) {
        this.wireName = wireName;
        this.hasIndex = hasIndex;
    }

    /**
     * The lower-case token this reference is spelled as — both as the
     * {@code highlight_entity} parameter name and as the auto key's prefix.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Whether this reference is completed by a server index. False for
     * {@link #SELF} only.
     */
    public boolean hasIndex() {
        return hasIndex;
    }
}
