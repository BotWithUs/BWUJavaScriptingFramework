package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.snapshot.Orientation;

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
 * @param spotAnimId     first active spot anim (graphic) id, or -1 if none
 * @param orientation    decoded facing (v21); unknown for 0xFFFF or an out-of-contract value
 * @param name           display name (v24), decoded from UTF-8 up to the first NUL; empty
 *                       until the client has decoded the player's appearance, never null
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
        int combatLevel,
        int spotAnimId,
        Orientation orientation,
        String name
) {
    public boolean isMoving() {
        return (flags & Layout.FLAG_MOVING) != 0;
    }

    /** True when {@link #name()} is a prefix of a longer name ({@link Layout#FLAG_NAME_TRUNCATED}). */
    public boolean isNameTruncated() {
        return (flags & Layout.FLAG_NAME_TRUNCATED) != 0;
    }
}
