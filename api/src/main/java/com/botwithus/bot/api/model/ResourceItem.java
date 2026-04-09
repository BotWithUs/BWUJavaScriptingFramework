package com.botwithus.bot.api.model;

/**
 * A single resource item obtainable from a world map element (e.g., an ore, fish, or log).
 *
 * @param itemId   the item ID (e.g., 453 for coal)
 * @param level    the level required to obtain this item
 * @param quantity the quantity obtainable
 * @see ResourceSection
 * @see WorldMapElement
 */
public record ResourceItem(
        int itemId,
        int level,
        int quantity
) {}
