package com.botwithus.bot.api.model;

/**
 * One variable a quest's progress is kept in, as the quest's cache definition names it:
 * which variable, the value it holds once the quest is started, and the value it reaches
 * when the quest is complete.
 *
 * @param kind       whether {@link #id()} is a varp or a varbit
 * @param id         the varp or varbit id
 * @param startValue the quest counts as started once the variable is above this value
 * @param endValue   the quest counts as complete once the variable is at or above this value
 * @see QuestType#progressVars()
 */
public record QuestVar(Kind kind, int id, int startValue, int endValue) {

    /** The id space {@link #id()} lives in. */
    public enum Kind {
        VARP,
        VARBIT
    }

    public QuestVar {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
    }
}
