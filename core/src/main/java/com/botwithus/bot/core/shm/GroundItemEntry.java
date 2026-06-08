package com.botwithus.bot.core.shm;

/**
 * One row of the ground-items array in a snapshot. See
 * {@link Layout#GROUND_ITEM_ENTRY_SIZE} for the byte layout.
 *
 * <p>Rows arrive from the producer's ObjStackList walk: every Alive()
 * ObjEntry within the loaded-scene tile bounds with a non-negative itemId.
 * The walker emits all live stacks regardless of plane — consumers spatially
 * filter on the immutable snapshot.</p>
 *
 * @param itemId    item type id (resolves via {@code GameAPI.getItemType})
 * @param quantity  stack size; 1 for non-stackable items
 * @param tileX     absolute world tile X
 * @param tileY     absolute world tile Y
 * @param plane     {@code 0..3}
 */
public record GroundItemEntry(
        int itemId,
        int quantity,
        int tileX,
        int tileY,
        int plane
) {
}
