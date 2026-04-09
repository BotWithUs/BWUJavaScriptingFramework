package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Represents a game entity (NPC, player, or object) returned by entity queries.
 *
 * @param handle            the unique handle for referencing this entity in subsequent API calls
 * @param serverIndex       the server-side index of the entity
 * @param typeId            the entity type/definition ID (e.g., NPC ID) — this is the parent/base ID used for interactions
 * @param tileX             the X tile coordinate
 * @param tileY             the Y tile coordinate
 * @param tileZ             the plane (height level, 0-3)
 * @param name              the display name of the entity
 * @param nameHash          the pre-computed hash of the entity name
 * @param isMoving          {@code true} if the entity is currently moving
 * @param isHidden          {@code true} if the entity is hidden/invisible
 * @param hitmarks          active hitmarks (damage splats) on this entity, empty if none
 * @param headbars          active headbars (HP bars) on this entity, empty if none
 * @param spotAnims         active spot animation IDs on this entity, empty if none
 * @param options           the resolved right-click options (transformed if applicable), empty if none
 * @param transformedTypeId the resolved type ID after varbit/varp transformation, or same as typeId if no transform
 * @see com.botwithus.bot.api.GameAPI#queryEntities
 */
public record Entity(
        int handle, int serverIndex, int typeId,
        int tileX, int tileY, int tileZ,
        String name, int nameHash,
        boolean isMoving, boolean isHidden,
        List<Hitmark> hitmarks, List<Headbar> headbars,
        List<Integer> spotAnims,
        List<String> options, int transformedTypeId
) {}
