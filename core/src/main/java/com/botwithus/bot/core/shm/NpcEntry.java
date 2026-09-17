package com.botwithus.bot.core.shm;

/**
 * One row of the NPC array in a snapshot. See {@link Layout#NPC_ENTRY_SIZE}
 * for the byte layout.
 *
 * @param serverIndex    server-side NPC slot
 * @param typeId         NPC type id, or -1 if not resolved
 * @param tileX          absolute world tile X (-1 if no graph node)
 * @param tileY          absolute world tile Y
 * @param plane          0..3
 * @param flags          bitset; bit 0 = moving (see {@link Layout#FLAG_MOVING})
 * @param followingIndex server index of follow target, or -1
 * @param animationId    current animation, or -1
 * @param stanceId       current stance
 * @param hp             current HP (NPC stats[3])
 * @param maxHp          max HP
 * @param spotAnimId     first active spot anim (graphic) id, or -1 if none
 */
public record NpcEntry(
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
        int spotAnimId
) {
    public boolean isMoving() {
        return (flags & Layout.FLAG_MOVING) != 0;
    }
}
