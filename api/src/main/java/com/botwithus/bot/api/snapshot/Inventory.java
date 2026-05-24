package com.botwithus.bot.api.snapshot;

import java.util.List;

/**
 * Snapshot of one inventory container.
 *
 * @param invId     game-side inventory id (matches {@code InventoryIds})
 * @param slotCount declared slot count for this container
 * @param items     one entry per slot in {@code [0, slotCount)}; empty
 *                  slots have {@code itemId == -1}. Defensive copy is taken.
 */
public record Inventory(
        int invId,
        int slotCount,
        List<InventoryItem> items
) {

    public Inventory {
        items = List.copyOf(items);
    }
}
