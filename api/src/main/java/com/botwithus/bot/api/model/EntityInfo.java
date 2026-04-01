package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Extended information about an entity, including health, animation, and combat state.
 *
 * @param handle         the unique entity handle
 * @param serverIndex    the server-side index
 * @param typeId         the entity type/definition ID
 * @param tileX          the X tile coordinate
 * @param tileY          the Y tile coordinate
 * @param tileZ          the plane (height level, 0-3)
 * @param name           the display name
 * @param nameHash       the pre-computed name hash
 * @param isMoving       {@code true} if the entity is currently moving
 * @param isHidden       {@code true} if the entity is hidden
 * @param health         the current health points
 * @param maxHealth      the maximum health points
 * @param animationId    the current animation ID, or {@code -1} if idle
 * @param stanceId       the current stance/idle animation ID
 * @param followingIndex the server index of the entity being followed, or {@code -1}
 * @param overheadText   any overhead text displayed, or {@code null}
 * @param combatLevel    the combat level of the entity
 * @param hitmarks       active hitmarks (damage splats) on this entity, empty if none
 * @param headbars       active headbars (HP bars) on this entity, empty if none
 * @param spotAnims      active spot animation IDs on this entity, empty if none
 * @see com.botwithus.bot.api.GameAPI#getEntityInfo
 */
public record EntityInfo(
        int handle, int serverIndex, int typeId,
        int tileX, int tileY, int tileZ,
        String name, int nameHash,
        boolean isMoving, boolean isHidden,
        int health, int maxHealth,
        int animationId, int stanceId,
        int followingIndex,
        String overheadText, int combatLevel,
        List<Hitmark> hitmarks, List<Headbar> headbars,
        List<Integer> spotAnims
) {}
