package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Quest definition from the game cache.
 *
 * @param id                     the quest type ID
 * @param name                   the quest name
 * @param listName               the display name in the quest list, or empty
 * @param category               the quest category
 * @param difficulty              the quest difficulty rating
 * @param membersOnly            whether this quest requires membership
 * @param questPoints            the quest points rewarded on completion
 * @param questPointReq          the quest points required to start
 * @param questItemSprite        the quest journal item sprite ID, or {@code -1} for none
 * @param startLocations         the tile coordinates of quest start locations
 * @param alternateStartLocation the alternate start location identifier
 * @param dependentQuestIds      IDs of quests that must be completed first
 * @param skillRequirements      the skill requirements to start the quest
 * @param progressVarps          the varps used to track quest progress
 * @param progressVarbits        the varbits used to track quest progress
 * @param params                 additional key-value parameters from the quest definition
 * @see com.botwithus.bot.api.GameAPI#getQuestType
 */
public record QuestType(
        int id,
        String name,
        String listName,
        int category,
        int difficulty,
        boolean membersOnly,
        int questPoints,
        int questPointReq,
        int questItemSprite,
        List<Integer> startLocations,
        int alternateStartLocation,
        List<Integer> dependentQuestIds,
        List<Map<String, Object>> skillRequirements,
        List<Map<String, Object>> progressVarps,
        List<Map<String, Object>> progressVarbits,
        Map<String, Object> params
) {

    /** The integer value of param {@code paramId}, or empty when the quest has none. */
    public OptionalInt intParam(int paramId) {
        return ConfigValues.intParam(params, paramId);
    }

    /** The string value of param {@code paramId}, or empty when the quest has none. */
    public Optional<String> stringParam(int paramId) {
        return ConfigValues.stringParam(params, paramId);
    }
}
