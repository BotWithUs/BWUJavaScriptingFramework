package com.botwithus.bot.api.model;

/**
 * A single world map icon placement returned by
 * {@link com.botwithus.bot.api.GameAPI#queryWorldMapIcons queryWorldMapIcons}.
 * Each result corresponds to one on-map icon (a placement of a
 * {@link WorldMapElement}) with absolute world tile coordinates.
 *
 * <p>When the query is run with {@code enrich = true} (the default), the
 * {@code spriteId}, {@code category}, {@code name}, and {@code tooltip}
 * fields are populated from the owning element. With {@code enrich = false}
 * those fields default to {@code -1} / empty strings for reduced payload.</p>
 *
 * @param worldMapElementId the owning {@link WorldMapElement} id
 * @param plane             the plane (height level) of the placement
 * @param worldX            the absolute world tile X coordinate
 * @param worldY            the absolute world tile Y coordinate
 * @param membersOnly       whether this placement is in a members-only area
 * @param spriteId          the icon sprite id (enriched), or {@code -1}
 * @param category          the element category (enriched), or {@code -1}
 * @param name              the element display name (enriched), or empty
 * @param tooltip           the element tooltip (enriched), or empty
 * @see WorldMapElement
 * @see WorldMapPlacement
 * @see com.botwithus.bot.api.query.WorldMapIconFilter
 */
public record WorldMapIconResult(
        int worldMapElementId,
        int plane,
        int worldX,
        int worldY,
        boolean membersOnly,
        int spriteId,
        int category,
        String name,
        String tooltip
) {}
