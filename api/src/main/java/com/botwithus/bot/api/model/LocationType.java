package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Location (game object) definition from the game cache.
 *
 * @param id           the location type ID
 * @param name         the location display name
 * @param sizeX        the X dimension in tiles
 * @param sizeY        the Y dimension in tiles
 * @param interactType the interaction type
 * @param solidType    the collision/solid type
 * @param members      whether this is members-only
 * @param options      the right-click interaction options
 * @param varbitId     the varbit controlling transformation, or {@code -1} if none
 * @param varpId       the varp controlling transformation, or {@code -1} if none
 * @param transforms   the list of type IDs this location can transform into
 * @param mapSpriteId  the minimap sprite ID, or {@code 0} if none
 * @param params       additional key-value parameters from the location definition
 * @see com.botwithus.bot.api.GameAPI#getLocationType
 */
public record LocationType(
        int id,
        String name,
        int sizeX,
        int sizeY,
        int interactType,
        int solidType,
        boolean members,
        List<String> options,
        int varbitId,
        int varpId,
        List<Integer> transforms,
        int mapSpriteId,
        Map<String, Object> params
) {

    /** The integer value of param {@code paramId}, or empty when the location has none. */
    public OptionalInt intParam(int paramId) {
        return ConfigValues.intParam(params, paramId);
    }

    /** The string value of param {@code paramId}, or empty when the location has none. */
    public Optional<String> stringParam(int paramId) {
        return ConfigValues.stringParam(params, paramId);
    }
}
