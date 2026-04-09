package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A group of resource items available at a world map element, keyed by a title
 * (e.g., "Coal rock", "Runite rock", "Shark").
 *
 * @param title the display title for this resource group
 * @param items the individual resource items in this group
 * @see ResourceItem
 * @see WorldMapElement
 */
public record ResourceSection(
        String title,
        List<ResourceItem> items
) {}
