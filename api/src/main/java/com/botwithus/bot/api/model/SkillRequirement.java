package com.botwithus.bot.api.model;

/**
 * A skill requirement associated with a world map element.
 *
 * @param skillId   the skill ID (e.g., 13 for Mining)
 * @param level     the required level
 * @param skillName the human-readable skill name (e.g., "Mining")
 * @see WorldMapElement
 */
public record SkillRequirement(
        int skillId,
        int level,
        String skillName
) {}
