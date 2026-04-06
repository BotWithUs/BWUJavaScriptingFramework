package com.botwithus.bot.api.model;

/**
 * A world map element (icon) from the game cache. These are static map features
 * such as banks, altars, dungeons, etc. with their absolute tile coordinates.
 *
 * @param id        the unique element ID
 * @param tileX     the absolute tile X coordinate
 * @param tileY     the absolute tile Y coordinate
 * @param plane     the plane (height level)
 * @param category  the category ID (e.g., 40 for banks)
 * @param spriteId  the map sprite ID
 * @param elementId the element definition ID
 * @param name      the display name (e.g., "Bank")
 * @param tooltip   the tooltip text (e.g., "Grand Exchange bank")
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
        String tooltip
) {}
