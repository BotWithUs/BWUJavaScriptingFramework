package com.botwithus.bot.api.model;

/**
 * A single resource line on a {@link WorldMapElement} — typically an ore,
 * fish, or log obtainable at the spot.
 *
 * @param itemId   item type id (e.g. 453 for coal)
 * @param level    minimum level to obtain
 * @param quantity quantity per harvest action when applicable; {@code 0}
 *                 for "unspecified"
 */
public record ResourceItem(int itemId, int level, int quantity) {}
