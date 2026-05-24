package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one NPC at the producer's current tick.
 *
 * @param serverIndex    server-side NPC slot
 * @param typeId         NPC type id, or {@code -1} if not resolved
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving (see {@link #isMoving()})
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param hp             current HP
 * @param maxHp          max HP
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
        int maxHp
) {

    /** Bit 0 of {@link #flags()}; mirrors {@code FLAG_MOVING} on the wire. */
    private static final int FLAG_MOVING = 1;

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }
}
