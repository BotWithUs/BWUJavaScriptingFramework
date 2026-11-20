package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Waiter;

import java.util.regex.Pattern;

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

    /**
     * {@link #interactFirst(int, String) interactFirst(itemId, "Use")}. "Use" is resolved by
     * the item's cache options, not the slot's live ops, as it always was; see
     * {@link InventoryContainer#USE} for why and for what is not known about it.
     */
    public boolean use(int itemId) {
        return interactFirst(itemId, USE);
    }

    /** {@link #use(int)} on the first item named exactly {@code name}, ignoring case. */
    public boolean use(String name) {
        return interactFirst(name, USE);
    }

    /** {@link #use(int)} on the first item whose whole name matches {@code namePattern}. */
    public boolean use(Pattern namePattern) {
        return interactFirst(namePattern, USE);
    }

    /**
     * Uses {@code item} on {@code target}, another backpack item, and waits for the game to
     * take it: the knife on a log. Blocks for up to a few ticks; see {@link UseOn}.
     */
    public UseOn.Result useOn(InventoryItem item, InventoryItem target) {
        return useOn().onItem(item, target);
    }

    /** Uses {@code item} on {@code npc}; see {@link UseOn}. */
    public UseOn.Result useOn(InventoryItem item, Npc npc) {
        return useOn().onNpc(item, npc);
    }

    /** Uses {@code item} on the loc {@code object}; see {@link UseOn}. */
    public UseOn.Result useOn(InventoryItem item, SceneObject object) {
        return useOn().onObject(item, object);
    }

    private UseOn useOn() {
        return new UseOn(api, this, Waiter.TIMING);
    }
}
