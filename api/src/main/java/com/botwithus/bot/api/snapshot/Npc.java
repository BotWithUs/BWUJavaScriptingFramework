package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one NPC at the producer's current tick.
 *
 * @param serverIndex    server-side NPC slot
 * @param typeId         the <b>base</b> NPC type id the server sent, or {@code -1} if not
 *                       resolved. This is identity, not appearance: a morphing NPC's name,
 *                       options and combat level come from {@code activeTypeId}
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving (see {@link #isMoving()})
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param hp             current HP
 * @param maxHp          max HP
 * @param spotAnimId     first active spot anim (graphic) id, or {@code -1} if none
 * @param orientation    rendered facing (wire v21); {@link Orientation#unknown()} when not read
 * @param activeTypeId   the morph-resolved type id (wire v24): the one the client names, shows
 *                       options for and takes the combat level from. Equals {@code typeId} for an
 *                       NPC with no morph; {@link #NO_ACTIVE_TYPE} when the morph resolves to
 *                       nothing, which the client treats as hidden (see {@link #isHidden()})
 * @param combatLevel    per-instance combat level as the client displays it (wire v24; {@code 0}
 *                       is real, for a non-combat NPC), or {@link #COMBAT_LEVEL_UNKNOWN} on a
 *                       record built without one
 */
public record Npc(
        int serverIndex,
        int typeId,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int hp,
        int maxHp,
        int spotAnimId,
        Orientation orientation,
        int activeTypeId,
        int combatLevel
) {

    /** {@link #activeTypeId()} when the morph resolves to nothing. */
    public static final int NO_ACTIVE_TYPE = -1;

    /** {@link #combatLevel()} on a record built with a pre-v24 constructor. */
    public static final int COMBAT_LEVEL_UNKNOWN = -1;

    /** Builds a row with no morph and no combat level; kept so pre-v24 callers compile unchanged. */
    public Npc(int serverIndex, int typeId, int tileX, int tileY, int plane, int flags,
               int followingIndex, int animationId, int stanceId, int hp, int maxHp,
               int spotAnimId, Orientation orientation) {
        this(serverIndex, typeId, tileX, tileY, plane, flags, followingIndex, animationId,
                stanceId, hp, maxHp, spotAnimId, orientation, typeId, COMBAT_LEVEL_UNKNOWN);
    }

    /** Builds a row with an unknown facing; kept so pre-v21 callers compile unchanged. */
    public Npc(int serverIndex, int typeId, int tileX, int tileY, int plane, int flags,
               int followingIndex, int animationId, int stanceId, int hp, int maxHp,
               int spotAnimId) {
        this(serverIndex, typeId, tileX, tileY, plane, flags, followingIndex, animationId,
                stanceId, hp, maxHp, spotAnimId, Orientation.unknown());
    }

    /** Bit 0 of {@link #flags()}; mirrors {@code FLAG_MOVING} on the wire. */
    private static final int FLAG_MOVING = 1;

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }

    /**
     * True when this NPC's morph resolves to nothing, so the client hides it. A row whose base
     * type did not resolve either ({@code typeId == -1}) is <em>unresolved</em>, not hidden, and
     * reads false. This is the same test as the client's own NPC visibility check.
     */
    public boolean isHidden() {
        return activeTypeId == NO_ACTIVE_TYPE && typeId != NO_ACTIVE_TYPE;
    }

    /** True when the NPC is shown as a different type than the base one the server sent. */
    public boolean isMorphed() {
        return activeTypeId != NO_ACTIVE_TYPE && activeTypeId != typeId;
    }

    /**
     * The id to look the definition up by: {@link #activeTypeId()} when there is one, else the
     * base {@link #typeId()}.
     */
    public int appearanceTypeId() {
        return activeTypeId != NO_ACTIVE_TYPE ? activeTypeId : typeId;
    }
}
