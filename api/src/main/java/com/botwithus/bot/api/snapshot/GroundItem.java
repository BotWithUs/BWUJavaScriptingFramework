package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one ground-item stack at the producer's current tick (v15+).
 *
 * <p>Rows come from the producer's ObjStackList walk; every alive bucket
 * entry within the loaded-scene tile bounds is emitted. Consumers spatially
 * filter on the immutable snapshot.</p>
 *
 * @param itemId    item type id (resolves via {@code GameAPI.getItemType})
 * @param quantity  stack size; 1 for non-stackable items
 * @param tileX     absolute world tile X
 * @param tileY     absolute world tile Y
 * @param plane     {@code 0..3}
 */
public record GroundItem(
        int itemId,
        int quantity,
        int tileX,
        int tileY,
        int plane
) {
}
