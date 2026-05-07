package com.botwithus.bot.api.model;

/**
 * Snapshot of one of the local player's skill stats.
 *
 * <p>Distinct from the wire-level {@code api.snapshot.Skill} record: this is
 * the script-facing model with a stable {@link #level()} accessor (returns
 * the unboosted base level — the value most level-gated content checks
 * against).</p>
 *
 * @param skillId      skill type id ({@code StatType.id} from the cache)
 * @param actualLevel  base/true level (e.g. 99); the "real" level
 * @param boostedLevel current displayed level including buffs/drains
 * @param experience   total xp; fits in {@code i32} (in-game cap is 200,000,000)
 * @see com.botwithus.bot.api.GameAPI#getPlayerStat
 */
public record PlayerStat(
        int skillId,
        int actualLevel,
        int boostedLevel,
        int experience
) {
    /**
     * The base (unboosted) level. Equivalent to {@link #actualLevel()};
     * named {@code level} for ergonomics in level-gated scripts where
     * "do I have level N" is the common question.
     */
    public int level() { return actualLevel; }
}
