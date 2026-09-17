package com.botwithus.bot.api.model;

/**
 * A skill requirement associated with a {@link WorldMapElement}.
 *
 * @param skillId   skill type id (matches in-game {@code StatType.id};
 *                  e.g. 26 = Divination, 13 = Mining)
 * @param level     required level
 * @param skillName human-readable skill name (e.g. "Mining"); may be
 *                  empty when the producer doesn't include it
 */
public record SkillRequirement(int skillId, int level, String skillName) {}
