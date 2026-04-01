package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Information about the local (logged-in) player.
 *
 * @param serverIndex  the player's server index
 * @param name         the player's display name
 * @param tileX        the X tile coordinate
 * @param tileY        the Y tile coordinate
 * @param plane        the plane (height level, 0-3)
 * @param isMember     {@code true} if the player is a member
 * @param isMoving     {@code true} if the player is currently moving
 * @param animationId  the current animation ID, or {@code -1} if idle
 * @param stanceId     the current stance/idle animation ID
 * @param health       the current health points
 * @param maxHealth    the maximum health points
 * @param combatLevel  the player's combat level
 * @param overheadText any overhead text displayed, or {@code null}
 * @param targetIndex  the server index of the entity being targeted, or {@code -1}
 * @param targetType   the type of the current target (0 = none)
 * @param hitmarks     active hitmarks (damage splats) on this player, empty if none
 * @param headbars     active headbars (HP bars) on this player, empty if none
 * @param spotAnims    active spot animation IDs on this player, empty if none
 * @see com.botwithus.bot.api.GameAPI#getLocalPlayer
 */
public record LocalPlayer(
        int serverIndex, String name,
        int tileX, int tileY, int plane,
        boolean isMember, boolean isMoving,
        int animationId, int stanceId,
        int health, int maxHealth, int combatLevel,
        String overheadText, int targetIndex, int targetType,
        List<Hitmark> hitmarks, List<Headbar> headbars,
        List<Integer> spotAnims
) {}
