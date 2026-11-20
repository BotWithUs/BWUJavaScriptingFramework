package com.botwithus.bot.api.quest;

import com.botwithus.bot.api.model.QuestVar;

import java.util.Optional;

/**
 * A quest's state for the logged-in player, read once.
 *
 * @param questId  the id of the record read: the one asked about, or for an unlisted stub
 *                 record the listed quest of the same name (see {@link QuestLog})
 * @param name     the quest's name from its definition; empty when there is no definition
 * @param stage    where the quest stands
 * @param progress the raw value of the progress variable, or {@link #UNKNOWN_PROGRESS}
 * @param variable the variable the stage was read from; empty when the stage is
 *                 {@link QuestStage#UNKNOWN} for want of one
 */
public record QuestStatus(int questId, String name, QuestStage stage, int progress,
                          Optional<QuestVar> variable) {

    /** {@link #progress()} when the value could not be read. */
    public static final int UNKNOWN_PROGRESS = -1;

    public QuestStatus {
        if (stage == null) {
            throw new IllegalArgumentException("stage");
        }
        name = name == null ? "" : name;
        variable = variable == null ? Optional.empty() : variable;
    }

    /** The status of a quest nothing could be read for. */
    static QuestStatus unknown(int questId, String name) {
        return new QuestStatus(questId, name, QuestStage.UNKNOWN, UNKNOWN_PROGRESS, Optional.empty());
    }

    /** True once the quest has been started, including when it is complete. */
    public boolean isStarted() {
        return stage == QuestStage.STARTED || stage == QuestStage.COMPLETE;
    }

    public boolean isComplete() {
        return stage == QuestStage.COMPLETE;
    }
}
