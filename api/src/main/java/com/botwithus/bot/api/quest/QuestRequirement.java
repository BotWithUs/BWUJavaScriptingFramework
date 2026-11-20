package com.botwithus.bot.api.quest;

/** One requirement for starting a quest that the player does not meet. */
public sealed interface QuestRequirement {

    /** A skill below the level the quest needs. */
    record Skill(int skillId, int required, int actual) implements QuestRequirement {}

    /** A quest that must be complete first and is not (or could not be read). */
    record PriorQuest(int questId) implements QuestRequirement {}

    /** A members' quest, and the player is not a member. */
    record Membership() implements QuestRequirement {}
}
