package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one player at the producer's current tick.
 *
 * @param serverIndex    server-side player slot
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving (see {@link #isMoving()})
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param combatLevel    combat level, or {@code 0} if not computed
 * @param spotAnimId     first active spot anim (graphic) id, or {@code -1} if none
 */
public record Player(
        int serverIndex,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int combatLevel,
        int spotAnimId
) {

    private static final int FLAG_MOVING = 1;

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }
}
