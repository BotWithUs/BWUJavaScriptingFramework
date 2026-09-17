package com.botwithus.bot.quest;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compile-time identity for a quest: the cache id, the wiki display name,
 * and the varp/varbit ids that track its progress.
 *
 * <p>Instances are produced by the build-time {@link Quests} codegen — one
 * {@code public static final} field per F2P quest in
 * {@code merged_quests.json}. Everything else (skill requirements,
 * dependent quests, start tile, QP reward) resolves at runtime through
 * {@link com.botwithus.bot.api.GameAPI#getQuestType(int)} so it tracks
 * cache changes; the codegen only freezes the identity tuple here.</p>
 *
 * @param id           the quest type id, matching
 *                     {@link com.botwithus.bot.api.model.QuestType#id()}
 * @param name         the wiki display name
 * @param trackerVars  the varp/varbit ids whose tuple of values uniquely
 *                     identifies the current progress state; preferred
 *                     source is {@code action_chains_sample.json::tracker}
 *                     when present, else the first entry from
 *                     {@code progressVarps} / {@code progressVarbits}
 */
public record QuestId(int id, String name, int[] trackerVars) {

    public QuestId {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trackerVars, "trackerVars");
        trackerVars = trackerVars.clone();
    }

    @Override
    public int[] trackerVars() {
        return trackerVars.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuestId q
                && q.id == id
                && q.name.equals(name)
                && Arrays.equals(q.trackerVars, trackerVars);
    }

    @Override
    public int hashCode() {
        return id * 31 + Arrays.hashCode(trackerVars);
    }

    @Override
    public String toString() {
        return "QuestId[" + id + ", " + name + ", " + Arrays.toString(trackerVars) + "]";
    }
}
