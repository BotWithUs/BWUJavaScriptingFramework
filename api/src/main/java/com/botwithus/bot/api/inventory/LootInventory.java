package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.snapshot.InventoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The loot window ({@code TOPLEVEL_V2_LOOT}, 1622) and the loot inventory behind it
 * ({@code LOOT}, inventory 773). Obtained from {@link GameAPI#loot()}.
 *
 * <p>The contents come from the snapshot, which publishes every inventory the client holds,
 * so they read without a round-trip whenever the client has the loot inventory, open window
 * or not. Taking, looting all and closing click the window's own options by name ({@value
 * #TAKE}, {@value #LOOT_ALL}, {@value #CLOSE}, the names xapi used), found over the window's
 * component tree and its live ops, so they need the window open and return {@code false},
 * queueing nothing, when it is not or the option is not listed.</p>
 */
public final class LootInventory {

    private static final Logger log = LoggerFactory.getLogger(LootInventory.class);

    private static final int INVENTORY_ID = 773;
    private static final int INTERFACE_ID = 1622;
    private static final GamevalRef INVENTORY = new GamevalRef(GamevalType.INV, "LOOT",
            INVENTORY_ID);
    private static final GamevalRef INTERFACE = new GamevalRef(GamevalType.INTERFACE,
            "TOPLEVEL_V2_LOOT", INTERFACE_ID);
    /** Area loot: picking up an item opens this window instead of taking the one item. */
    private static final GamevalRef AREA_LOOT = new GamevalRef(GamevalType.VARBIT,
            "LOOT_BY_AREA", 27943);

    static final String TAKE = "Take";
    static final String LOOT_ALL = "Loot All";
    static final String CLOSE = "Close";
    private static final int ENABLED = 1;

    private final GameAPI api;

    public LootInventory(GameAPI api) {
        this.api = api;
    }

    /** The loot inventory itself, for every read {@link InventoryContainer} offers. */
    public InventoryContainer items() {
        return new InventoryContainer(api, INVENTORY.resolve(api.gamevals()));
    }

    /** The filled slots of the loot inventory, in slot order. */
    public List<InventoryItem> contents() {
        return items().getItems();
    }

    /** True when the loot window is open. Snapshot read: no round-trip. */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE.resolve(api.gamevals()));
    }

    /** True when the game's Area Loot setting is on, so {@link #open()} opens the window. */
    public boolean isAreaLootEnabled() {
        return api.getVarbit(AREA_LOOT.resolve(api.gamevals())) == ENABLED;
    }

    /**
     * Opens the loot window the way a player does with Area Loot on: {@value #TAKE} on the
     * nearest ground item. With Area Loot off this picks that one item up instead, so it
     * refuses then.
     *
     * @return {@code true} when Area Loot is on and the click was queued
     */
    public boolean open() {
        if (!isAreaLootEnabled()) {
            return false;
        }
        GroundItem nearest = api.groundItems().query().nearest();
        return nearest != null && nearest.interact(TAKE);
    }

    /** Presses the window's Close. */
    public boolean close() {
        return press(CLOSE);
    }

    /** Presses the window's Loot All. */
    public boolean lootAll() {
        return press(LOOT_ALL);
    }

    /** Takes the stack of {@code itemId} shown in the window. */
    public boolean take(int itemId) {
        if (!isOpen()) {
            return false;
        }
        ComponentNode slot = api.components().in(INTERFACE.resolve(api.gamevals()))
                .withItemId(itemId).withOption(TAKE).first();
        return slot != null && slot.interact(TAKE);
    }

    /** Takes the first stack in the loot inventory named exactly (ignoring case) {@code name}. */
    public boolean take(String name) {
        InventoryItem item = items().getFirst(name);
        return item != null && take(item.itemId());
    }

    private boolean press(String option) {
        if (!isOpen()) {
            return false;
        }
        ComponentNode button = api.components().in(INTERFACE.resolve(api.gamevals()))
                .withOption(option).first();
        if (button == null) {
            log.debug("loot window lists no '{}' option", option);
            return false;
        }
        return button.interact(option);
    }
}
