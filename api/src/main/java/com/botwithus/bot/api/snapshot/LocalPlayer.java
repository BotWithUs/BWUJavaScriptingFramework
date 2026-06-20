package com.botwithus.bot.api.snapshot;

import java.util.List;

/**
 * Snapshot of the local (logged-in) player at the producer's current tick.
 * Returned by {@link GameSnapshot#self()}; {@code null} when the client is
 * not in-game.
 *
 * @param serverIndex    server-side player slot
 * @param combatLevel    combat level, or {@code 0} if not computed
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param targetIndex    server index of the current target, or {@code -1}
 * @param targetType     target type discriminator, or {@code 0} for none
 * @param isMember       member-status flag from the producer
 * @param spotAnimId     first active spot anim (graphic) id, or {@code -1} if none
 * @param skills         live skills array; defensive copy taken on construction
 */
public record LocalPlayer(
        int serverIndex,
        int combatLevel,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int targetIndex,
        int targetType,
        boolean isMember,
        int spotAnimId,
        List<Skill> skills
) {

    private static final int FLAG_MOVING = 1;

    public LocalPlayer {
        skills = List.copyOf(skills);
    }

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }
}
