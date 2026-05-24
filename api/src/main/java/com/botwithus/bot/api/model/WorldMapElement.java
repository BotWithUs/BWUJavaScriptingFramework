package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A world-map element from the game cache. These are static map features
 * (banks, altars, divination spots, mining nodes, ...) with absolute tile
 * coordinates, skill requirements, and resource breakdowns. Returned by
 * {@link com.botwithus.bot.api.GameAPI#queryWorldMapElements queryWorldMapElements}
 * and by the {@link com.botwithus.bot.api.entities.WorldMapElements} facade.
 *
 * <p>Unlike {@link com.botwithus.bot.api.entities.SceneObject SceneObject}
 * (live runtime data) this is cache-resident; queries hit the producer once
 * and the result is stable until a game update changes the cache.</p>
 *
 * @param id                element id (cache-resident)
 * @param tileX             absolute tile X (canonical placement)
 * @param tileY             absolute tile Y
 * @param plane             0..3
 * @param category          category id (e.g. 3032 for divination)
 * @param spriteId          minimap sprite id, or {@code 0} when none
 * @param elementId         element definition id (separate from {@link #id} —
 *                          the cache splits "where" from "what")
 * @param name              display name (e.g. "Bank")
 * @param tooltip           tooltip text (e.g. "Grand Exchange bank")
 * @param description       free-form description (e.g. "Mining Guild");
 *                          may be empty
 * @param minLevel          minimum level shown on the element badge, or
 *                          {@code -1} if none
 * @param levelTier1        first level tier, or {@code -1} if none
 * @param levelTier2        second level tier, or {@code -1}
 * @param levelTier3        third level tier, or {@code -1}
 * @param skillRequirements skill requirements; defensively copied
 * @param resources         resource sections (ores/fish/logs/...); defensively copied
 * @param placements        every absolute tile placement for this element;
 *                          defensively copied
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
) {
    public WorldMapElement {
        skillRequirements = List.copyOf(skillRequirements);
        resources         = List.copyOf(resources);
        placements        = List.copyOf(placements);
    }
}
