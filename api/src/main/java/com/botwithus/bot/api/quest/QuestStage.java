package com.botwithus.bot.api.quest;

/** Where a quest stands for the logged-in player. */
public enum QuestStage {
    /** The progress variable is below the quest's start value. */
    NOT_STARTED,
    /** At or above the start value and below the end value. */
    STARTED,
    /** At or above the end value. */
    COMPLETE,
    /**
     * Not known: no cache definition, a definition with no progress variable, or a variable
     * that could not be read (not in game, or still entering the world).
     */
    UNKNOWN
}
