package com.botwithus.bot.api.model;

import java.util.ArrayList;
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

    /** Keys of one {@link #progressVarps()} / {@link #progressVarbits()} entry. */
    private static final String VAR_ID = "id";
    private static final String START_VALUE = "threshold1";
    private static final String END_VALUE = "threshold2";
    /** Keys of one {@link #skillRequirements()} entry. */
    private static final String SKILL = "skill";
    private static final String LEVEL = "level";

    /**
     * The variables this quest's progress is kept in, varps first and then varbits, in the
     * order the definition lists them. Entries whose numbers did not decode are left out.
     */
    public List<QuestVar> progressVars() {
        List<QuestVar> vars = new ArrayList<>();
        addVars(vars, QuestVar.Kind.VARP, progressVarps);
        addVars(vars, QuestVar.Kind.VARBIT, progressVarbits);
        return List.copyOf(vars);
    }

    /**
     * The skill levels needed to start this quest, as {@code skillId -> level} pairs.
     * Entries whose numbers did not decode are left out; {@code skillName} is empty, because
     * the cache definition carries none.
     */
    public List<SkillRequirement> requiredSkills() {
        List<SkillRequirement> out = new ArrayList<>();
        if (skillRequirements == null) {
            return out;
        }
        for (Map<String, Object> entry : skillRequirements) {
            OptionalInt skill = ConfigValues.asInt(entry.get(SKILL));
            OptionalInt level = ConfigValues.asInt(entry.get(LEVEL));
            if (skill.isPresent() && level.isPresent()) {
                out.add(new SkillRequirement(skill.getAsInt(), level.getAsInt(), ""));
            }
        }
        return List.copyOf(out);
    }

    private static void addVars(List<QuestVar> out, QuestVar.Kind kind,
                                List<Map<String, Object>> raw) {
        if (raw == null) {
            return;
        }
        for (Map<String, Object> entry : raw) {
            OptionalInt id = ConfigValues.asInt(entry.get(VAR_ID));
            OptionalInt start = ConfigValues.asInt(entry.get(START_VALUE));
            OptionalInt end = ConfigValues.asInt(entry.get(END_VALUE));
            if (id.isPresent() && start.isPresent() && end.isPresent()) {
                out.add(new QuestVar(kind, id.getAsInt(), start.getAsInt(), end.getAsInt()));
            }
        }
    }

    /** The integer value of param {@code paramId}, or empty when the quest has none. */
    public OptionalInt intParam(int paramId) {
        return ConfigValues.intParam(params, paramId);
    }

    /** The string value of param {@code paramId}, or empty when the quest has none. */
    public Optional<String> stringParam(int paramId) {
        return ConfigValues.stringParam(params, paramId);
    }
}
