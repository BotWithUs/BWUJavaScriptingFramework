package com.botwithus.bot.api.event;

/**
 * Fired when a skill's experience, base level or boosted level differs from the previous
 * server tick.
 *
 * <p>The host derives this event by comparing the local player's skills tick to tick, with
 * the same baseline rules as {@link InventoryChangeEvent}: nothing until one in-game tick has
 * been seen, and the baseline is reset on leaving the game.</p>
 *
 * @param skillId        the skill id ({@code SkillType.id()})
 * @param oldExperience  experience on the previous tick
 * @param newExperience  experience now
 * @param oldLevel       base level on the previous tick
 * @param newLevel       base level now
 * @param oldBoosted     boosted (current) level on the previous tick
 * @param newBoosted     boosted (current) level now
 * @param serverTick     the server tick the new state was read on
 * @param timestamp      event creation time in milliseconds since epoch
 */
public record SkillChangeEvent(int skillId, int oldExperience, int newExperience, int oldLevel,
                               int newLevel, int oldBoosted, int newBoosted, int serverTick,
                               long timestamp) implements GameEvent {

    public SkillChangeEvent(int skillId, int oldExperience, int newExperience, int oldLevel,
                            int newLevel, int oldBoosted, int newBoosted, int serverTick) {
        this(skillId, oldExperience, newExperience, oldLevel, newLevel, oldBoosted, newBoosted,
                serverTick, System.currentTimeMillis());
    }

    /** Experience gained since the previous tick; negative only if the game took some away. */
    public int experienceGained() {
        return newExperience - oldExperience;
    }

    /** Base levels gained since the previous tick. */
    public int levelsGained() {
        return newLevel - oldLevel;
    }

    /** True when the base level went up. */
    public boolean isLevelUp() {
        return newLevel > oldLevel;
    }
}
