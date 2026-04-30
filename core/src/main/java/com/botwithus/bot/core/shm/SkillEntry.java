package com.botwithus.bot.core.shm;

/**
 * One row of the local-player skills array.
 *
 * @param typeId       skill type id (StatType.id from the game)
 * @param experience   total xp
 * @param actualLevel  base/true level (e.g. 99)
 * @param boostedLevel current displayed level including buffs/drains
 */
public record SkillEntry(int typeId, int experience, int actualLevel, int boostedLevel) {}
