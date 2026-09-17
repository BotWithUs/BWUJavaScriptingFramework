package com.botwithus.bot.api.snapshot;

/**
 * One slot of an inventory snapshot.
 *
 * @param slot     slot index within the parent {@link Inventory}
 * @param itemId   item definition id, or {@code -1} for an empty slot
 * @param quantity stack size; {@code 0} when {@link #isEmpty()}
 */
public record InventoryItem(
        int slot,
        int itemId,
        int quantity
) {

    public boolean isEmpty() {
        return itemId == -1;
    }
}
