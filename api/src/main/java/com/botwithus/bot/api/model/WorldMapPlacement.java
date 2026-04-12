package com.botwithus.bot.api.model;

/**
 * A single placement of a world map element on the world map. A given element
 * may exist at multiple tiles (e.g., "Bank" has many placements); each entry
 * gives the absolute tile coordinates and whether the location is members-only.
 *
 * @param plane        the plane (height level)
 * @param tileX        the absolute tile X coordinate
 * @param tileY        the absolute tile Y coordinate
 * @param membersOnly  whether this placement is in a members-only area
 * @see WorldMapElement
 */
public record WorldMapPlacement(
        int plane,
        int tileX,
        int tileY,
        boolean membersOnly
) {}
