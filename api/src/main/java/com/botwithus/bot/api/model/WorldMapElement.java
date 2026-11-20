package com.botwithus.bot.api.model;

import java.util.List;

/**
 * One placement of a world-map element from the game cache: an area or place label, a
 * quest start, a lodestone, a fairy ring, a dungeon entrance. Returned by
 * {@link com.botwithus.bot.api.GameAPI#queryWorldMapElements queryWorldMapElements}
 * and by the {@link com.botwithus.bot.api.entities.WorldMapElements} facade.
 *
 * <p>Unlike {@link com.botwithus.bot.api.entities.SceneObject SceneObject}
 * (live runtime data) this is cache-resident, read through {@code NXTCache.dll}: the
 * placements from cache index 41 and the definition each names. It is stable until a game
 * update changes the cache.</p>
 *
 * <p><b>Coordinates are in the placement's area's map space</b> ({@link #areaId()}). That
 * is the world tile for nearly every placement, but the main overview area
 * ({@code WorldMapElements.MAIN_AREA_ID}) draws a few regions (Prifddinas, Ashdale) away
 * from where they really are: the Prifddinas lodestone is at (4576, 2665) in area 28 and
 * at (2208, 3361), its real tile, in area 231. Most minimap function icons (banks, altars,
 * trees) are not world map elements at all; they come from loc definitions.</p>
 *
 * <p>The cache supplies a placement and its definition only. {@code description}, the
 * level fields, the skill requirements and the resources are not in it and read empty /
 * {@code -1}; {@link WorldMapPlacement#membersOnly()} is not known and reads false.</p>
 *
 * @param id                the element's definition id
 * @param tileX             tile X in the area's map space
 * @param tileY             tile Y in the area's map space
 * @param plane             0..3
 * @param category          category id (the definition's category, e.g. 2613 for
 *                          home-teleport destinations, the lodestones)
 * @param spriteId          the definition's sprite id, {@code -1} when none
 * @param elementId         the element's definition id, the same as {@link #id()}
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
 * @param placements        this placement, as a {@link WorldMapPlacement}; defensively copied
 * @param areaId            the world map area the placement belongs to
 * @param flags             the placement record's raw trailing byte; meaning not pinned
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
        List<WorldMapPlacement> placements,
        int areaId,
        int flags
) {
    /** {@link #areaId()} of an element built with the constructor that predates it. */
    public static final int UNKNOWN_AREA = -1;

    public WorldMapElement {
        skillRequirements = List.copyOf(skillRequirements);
        resources         = List.copyOf(resources);
        placements        = List.copyOf(placements);
    }

    /** The shape before {@link #areaId()} existed: area unknown, flags 0. */
    public WorldMapElement(int id, int tileX, int tileY, int plane, int category, int spriteId,
                           int elementId, String name, String tooltip, String description,
                           int minLevel, int levelTier1, int levelTier2, int levelTier3,
                           List<SkillRequirement> skillRequirements, List<ResourceSection> resources,
                           List<WorldMapPlacement> placements) {
        this(id, tileX, tileY, plane, category, spriteId, elementId, name, tooltip, description,
                minLevel, levelTier1, levelTier2, levelTier3, skillRequirements, resources,
                placements, UNKNOWN_AREA, 0);
    }
}
