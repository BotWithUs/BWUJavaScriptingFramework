package com.botwithus.bot.api.model;

/**
 * Wire-format record returned by {@code query_ground_items} for one stack
 * of items lying on the ground.
 *
 * @param handle    opaque server-side handle for action queueing
 * @param itemId    ItemType id (resolve full definition via
 *                  {@link com.botwithus.bot.api.GameAPI#getItemType getItemType})
 * @param quantity  stack size (always &gt;= 1; the producer drops
 *                  empty stacks before serializing)
 * @param tileX     absolute world tile X
 * @param tileY     absolute world tile Y
 * @param plane     0..3
 */
public record GroundItemInfo(
        int handle,
        int itemId,
        int quantity,
        int tileX,
        int tileY,
        int plane
) {}
