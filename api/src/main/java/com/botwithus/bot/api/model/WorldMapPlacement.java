package com.botwithus.bot.api.model;

/**
 * A single placement of a {@link WorldMapElement} on the world map. A given
 * element may exist at multiple tiles (e.g. "Bank" has many placements);
 * each entry gives the absolute tile coordinates and member-only status.
 *
 * @param plane       0..3
 * @param tileX       absolute world tile X
 * @param tileY       absolute world tile Y
 * @param membersOnly whether this placement is in a members-only area
 */
public record WorldMapPlacement(int plane, int tileX, int tileY, boolean membersOnly) {}
