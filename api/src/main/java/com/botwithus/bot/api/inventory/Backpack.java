package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;

/**
 * The local player's backpack (inv id 93, slot grid at iface 1473 comp 5).
 * Singleton per {@link GameAPI}; obtain via {@code api.backpack()}.
 *
 * <p>Inherits all read/containment/interaction methods from
 * {@link InventoryContainer}. The slot click route is iface 1473 comp 5,
 * which is the {@link com.botwithus.bot.api.util.Interfaces#BACKPACK}
 * inventory grid.</p>
 */
public final class Backpack extends InventoryContainer {

    public static final int INVENTORY_ID = 93;
    public static final int INTERFACE_ID = 1473;
    public static final int COMPONENT_ID = 5;

    public Backpack(GameAPI api) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID);
    }

    /** Convenience: equivalent to {@link #interactFirst(int, String)} for clarity. */
    public boolean use(int itemId) {
        return interactFirst(itemId, "Use");
    }
}
