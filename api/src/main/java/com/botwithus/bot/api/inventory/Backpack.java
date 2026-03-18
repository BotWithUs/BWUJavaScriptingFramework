package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;

import java.util.List;

/**
 * Provides access to the player's backpack (inventory ID 93, interface 1473).
 * Ported from the legacy BotWithUs API to use the pipe RPC.
 */
public final class Backpack {

    /** RS3 backpack inventory ID. */
    public static final int INVENTORY_ID = 93;
    /** RS3 backpack interface ID. */
    public static final int INTERFACE_ID = 1473;
    /** RS3 backpack component ID within the interface. */
    public static final int COMPONENT_ID = 5;

    private final GameAPI api;
    private final InventoryContainer container;

    /**
     * Creates a new backpack wrapper.
     *
     * @param api the game API instance
     */
    public Backpack(GameAPI api) {
        this.api = api;
        this.container = new InventoryContainer(api, INVENTORY_ID);
    }

    /**
     * Returns the underlying {@link InventoryContainer} for advanced queries.
     *
     * @return the inventory container
     */
    public InventoryContainer container() {
        return container;
    }

    // ========================== Query Methods ==========================

    /**
     * Checks if the backpack has no items.
     *
     * @return {@code true} if the backpack is empty
     */
    public boolean isEmpty() {
        return container.isEmpty();
    }

    /**
     * Checks if the backpack has at least one item.
     *
     * @return {@code true} if the backpack is not empty
     */
    public boolean isNotEmpty() {
        return container.isNotEmpty();
    }

    /**
     * Checks if the backpack has no free slots.
     *
     * @return {@code true} if the backpack is full
     */
    public boolean isFull() {
        return container.isFull();
    }

    /**
     * Checks if the backpack has at least one free slot.
     *
     * @return {@code true} if the backpack is not full
     */
    public boolean isNotFull() {
        return container.isNotFull();
    }

    /**
     * Checks if the backpack contains the specified item.
     *
     * @param itemId the item ID to look for
     * @return {@code true} if the item is present
     */
    public boolean contains(int itemId) {
        return container.contains(itemId);
    }

    /**
     * Checks if the backpack contains at least the specified amount of an item.
     *
     * @param itemId the item ID to look for
     * @param amount the minimum quantity required
     * @return {@code true} if enough of the item is present
     */
    public boolean contains(int itemId, int amount) {
        return container.contains(itemId, amount);
    }

    /**
     * Checks if the backpack contains an item whose name contains the given string (case-insensitive).
     *
     * @param name the name substring to search for
     * @return {@code true} if a matching item is present
     */
    public boolean contains(String name) {
        return container.contains(name);
    }

    /**
     * Counts the total quantity of an item across all backpack slots.
     *
     * @param itemId the item ID to count
     * @return the total quantity
     */
    public int count(int itemId) {
        return container.count(itemId);
    }

    /**
     * Counts the total quantity of items whose name contains the given string (case-insensitive).
     *
     * @param name the name substring to search for
     * @return the total quantity of matching items
     */
    public int count(String name) {
        return container.count(name);
    }

    /**
     * Returns the item in a specific backpack slot.
     *
     * @param slot the slot index (0-based)
     * @return the inventory item in that slot
     */
    public InventoryItem getSlot(int slot) {
        return container.slot(slot);
    }

    /**
     * Returns all non-empty items in the backpack.
     *
     * @return a list of inventory items
     */
    public List<InventoryItem> getItems() {
        return container.getItems();
    }

    /**
     * Returns the number of occupied (non-empty) slots in the backpack.
     *
     * @return the occupied slot count
     */
    public int occupiedSlots() {
        return container.occupiedSlots();
    }

    /**
     * Returns the number of empty slots in the backpack.
     *
     * @return the free slot count
     */
    public int freeSlots() {
        return container.freeSlots();
    }

    /**
     * Returns all items including empty slots.
     *
     * @return a list of all inventory slots
     */
    public List<InventoryItem> getAllSlots() {
        return container.getAllSlots();
    }

    // ========================== Interaction Methods ==========================

    /**
     * Interact with an item in the backpack by item ID and option string.
     * Finds the component holding the item, looks up the option index, and queues an action.
     *
     * @param itemId the item ID to interact with
     * @param option the right-click option (e.g. "Drop", "Eat", "Equip")
     * @return true if the action was queued
     */
    public boolean interact(int itemId, String option) {
        Component comp = ComponentHelper.findComponentByItem(api, INTERFACE_ID, itemId);
        if (comp == null) return false;
        return ComponentHelper.interactComponent(api, comp, option);
    }

    /**
     * Interact with an item in the backpack by item ID and option index (1-based).
     *
     * @param itemId the item ID
     * @param optionIndex the 1-based option index
     * @return true if the action was queued
     */
    public boolean interact(int itemId, int optionIndex) {
        Component comp = ComponentHelper.findComponentByItem(api, INTERFACE_ID, itemId);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, optionIndex);
    }

    /**
     * "Use" an item (selectable component action) by item ID.
     *
     * @param itemId the item ID
     * @return true if the action was queued
     */
    public boolean use(int itemId) {
        Component comp = ComponentHelper.findComponentByItem(api, INTERFACE_ID, itemId);
        if (comp == null) return false;
        api.queueAction(new GameAction(
                ActionTypes.SELECT_COMPONENT_ITEM, 0, comp.subComponentId(), ComponentHelper.componentHash(comp)));
        return true;
    }
}
