package com.botwithus.bot.api.event;

import com.botwithus.bot.api.snapshot.InventoryItem;

/**
 * Fired when one slot of an inventory holds something different from the previous server
 * tick: a different item, or a different quantity of the same item.
 *
 * <p>The host derives this event; the agent does not send it. Once per server tick the host
 * compares each inventory with the copy it took the tick before and publishes one event per
 * changed slot. Three consequences follow, and a script should rely on all three:</p>
 * <ul>
 *   <li>An inventory that was not published on the previous tick (the bank before it was
 *       first opened, say) is taken as the baseline silently. Only inventories present on
 *       both ticks are compared, so opening the bank does not flood the bus.</li>
 *   <li>Changes inside one tick collapse: an item picked up and dropped within the same
 *       tick produces nothing.</li>
 *   <li>Nothing is published until the host has seen the local player in game for one
 *       tick, and the baseline is reset on leaving the game.</li>
 * </ul>
 *
 * @param invId      the inventory id (93 = backpack)
 * @param slot       the slot index
 * @param oldItem    what the slot held on the previous tick ({@link InventoryItem#isEmpty()}
 *                   when it was empty)
 * @param newItem    what the slot holds now
 * @param serverTick the server tick the new state was read on
 * @param timestamp  event creation time in milliseconds since epoch
 */
public record InventoryChangeEvent(int invId, int slot, InventoryItem oldItem,
                                   InventoryItem newItem, int serverTick, long timestamp)
        implements GameEvent {

    public InventoryChangeEvent(int invId, int slot, InventoryItem oldItem,
                                InventoryItem newItem, int serverTick) {
        this(invId, slot, oldItem, newItem, serverTick, System.currentTimeMillis());
    }

    /** True when the slot was empty and now holds an item. */
    public boolean isAdded() {
        return oldItem.isEmpty() && !newItem.isEmpty();
    }

    /** True when the slot held an item and is now empty. */
    public boolean isRemoved() {
        return !oldItem.isEmpty() && newItem.isEmpty();
    }

    /**
     * How many more of the slot's item there are now: positive when the stack grew,
     * negative when it shrank. When the slot changed from one item to another, this is the
     * new quantity.
     */
    public int quantityDelta() {
        if (oldItem.itemId() == newItem.itemId()) {
            return newItem.quantity() - oldItem.quantity();
        }
        return newItem.isEmpty() ? -oldItem.quantity() : newItem.quantity();
    }
}
