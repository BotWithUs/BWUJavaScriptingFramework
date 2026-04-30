package com.botwithus.bot.core.shm;

/**
 * One row of the player array in a snapshot.
 *
 * @param serverIndex    server-side player slot
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          0..3
 * @param flags          bitset; bit 0 = moving
 * @param followingIndex server index of follow target, or -1
 * @param animationId    current animation, or -1
 * @param stanceId       current stance
 * @param combatLevel    combat level, or 0 if not computed
 */
public record PlayerEntry(
        int serverIndex,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int combatLevel
) {
    public boolean isMoving() {
        return (flags & Layout.FLAG_MOVING) != 0;
    }
}
