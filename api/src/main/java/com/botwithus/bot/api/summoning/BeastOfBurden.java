package com.botwithus.bot.api.summoning;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.constants.InventoryIds;
import com.botwithus.bot.api.inventory.InventoryContainer;

/**
 * The summoned familiar's beast-of-burden inventory ({@code LORE_BOB}, inventory
 * {@value InventoryIds#FAMILIAR}). Reads are snapshot-backed like every other
 * {@link InventoryContainer}; the inventory is only published while a beast of burden is
 * summoned and has been opened at least once.
 */
public final class BeastOfBurden extends InventoryContainer {

    public BeastOfBurden(GameAPI api) {
        super(api, InventoryIds.FAMILIAR);
    }
}
