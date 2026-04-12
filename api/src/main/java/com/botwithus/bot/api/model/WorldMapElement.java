package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A world map element (icon) from the game cache. These are static map features
 * such as banks, altars, dungeons, etc. with their absolute tile coordinates,
 * skill requirements, and level data.
 *
 * @param id                the unique element ID
 * @param tileX             the absolute tile X coordinate
 * @param tileY             the absolute tile Y coordinate
 * @param plane             the plane (height level)
 * @param category          the category ID (e.g., 1159 for mining)
 * @param spriteId          the map sprite ID
 * @param elementId         the element definition ID
 * @param name              the display name (e.g., "Bank")
 * @param tooltip           the tooltip text (e.g., "Grand Exchange bank")
 * @param description       the description text (e.g., "Mining Guild"), may be empty
 * @param minLevel          the minimum level shown on the element, or -1 if none
 * @param levelTier1        the first level tier, or -1 if none
 * @param levelTier2        the second level tier, or -1 if none
 * @param levelTier3        the third level tier, or -1 if none
 * @param skillRequirements the skill requirements for this element, may be empty
 * @param resources         the resource sections available at this element (ores, fish, logs, etc.), may be empty
 * @param placements        every absolute tile placement for this element (an element may exist at multiple tiles)
 * @see SkillRequirement
 * @see ResourceSection
 * @see WorldMapPlacement
 * @see com.botwithus.bot.api.GameAPI#queryWorldMapElements
 * @see com.botwithus.bot.api.GameAPI#getWorldMapElement
 */
public record WorldMapElement(
        int id,
        int tileX,
        int tileY,
        int plane,
        int category,
        int spriteId,
        int elementId,
        String name,
        String tooltip,
        String description,
        int minLevel,
        int levelTier1,
        int levelTier2,
        int levelTier3,
        List<SkillRequirement> skillRequirements,
        List<ResourceSection> resources,
        List<WorldMapPlacement> placements
) {}
