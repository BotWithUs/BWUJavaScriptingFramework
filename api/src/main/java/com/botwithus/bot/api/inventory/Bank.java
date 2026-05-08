package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;

/**
 * The bank inventory (inv id 95). The bank UI lives at iface 517 with a
 * separate component for the bank slot grid. Singleton per {@link GameAPI};
 * obtain via {@code api.bank()}.
 *
 * <p>Inherits read/containment/interaction from {@link InventoryContainer}.
 * Slot interactions click the bank slot grid; deposit-from-backpack clicks
 * route through {@link Backpack#interactFirst(int, String)} on the
 * deposit-side iface instead.</p>
 */
public final class Bank extends InventoryContainer {

    public static final int INVENTORY_ID = 95;
    public static final int INTERFACE_ID = 517;
    public static final int COMPONENT_ID = 195;

    public Bank(GameAPI api) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID);
    }
}
