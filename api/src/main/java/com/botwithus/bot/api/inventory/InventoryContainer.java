package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.query.InventoryFilter;

import java.util.List;

/**
 * Wraps a game inventory (backpack, bank, equipment, etc.) and provides
 * convenient query methods over the pipe RPC.
 *
 * <p>This is the base wrapper used by specialized containers such as
 * {@link Backpack}, {@link Equipment}, and {@link Bank}.</p>
 *
 * @see Backpack
 * @see Equipment
 * @see Bank
 */
public class InventoryContainer {

    private final GameAPI api;
    private final int id;

    /**
     * Creates a new inventory container wrapper.
     *
     * @param api the game API instance
     * @param id  the inventory ID to wrap
     */
    public InventoryContainer(GameAPI api, int id) {
        this.api = api;
        this.id = id;
    }

    /**
     * Returns the inventory ID this container wraps.
     *
     * @return the inventory ID
     */
    public int getId() {
        return id;
    }

    /**
     * Get all non-empty items in this inventory.
     */
    public List<InventoryItem> getItems() {
        return api.queryInventoryItems(InventoryFilter.builder()
                .inventoryId(id)
                .nonEmpty(true)
                .build());
    }

    /**
     * Get all items including empty slots.
     */
    public List<InventoryItem> getAllSlots() {
        return api.queryInventoryItems(InventoryFilter.builder()
                .inventoryId(id)
                .nonEmpty(false)
                .build());
    }

    /**
     * Check if the inventory has no items.
     */
    public boolean isEmpty() {
        return getItems().isEmpty();
    }

    /**
     * Checks if the inventory has at least one item.
     *
     * @return {@code true} if the inventory contains any items
     */
    public boolean isNotEmpty() {
        return !isEmpty();
    }

    /**
     * Check if the inventory is full (no empty slots with item_id == -1).
     */
    public boolean isFull() {
        List<InventoryItem> allSlots = getAllSlots();
        if (allSlots.isEmpty()) return false;
        return allSlots.stream().noneMatch(i -> i.itemId() == -1);
    }

    /**
     * Check if the inventory contains an item with the given ID.
     */
    public boolean contains(int itemId) {
        return !api.queryInventoryItems(InventoryFilter.builder()
                .inventoryId(id)
                .itemId(itemId)
                .nonEmpty(true)
                .build()).isEmpty();
    }

    /**
     * Check if the inventory contains at least {@code amount} of the given item.
     */
    public boolean contains(int itemId, int amount) {
        int total = api.queryInventoryItems(InventoryFilter.builder()
                .inventoryId(id)
                .itemId(itemId)
                .nonEmpty(true)
                .build()).stream()
                .mapToInt(InventoryItem::quantity)
                .sum();
        return total >= amount;
    }

    /**
     * Get the item in a specific slot.
     */
    public InventoryItem slot(int slot) {
        return api.getInventoryItem(id, slot);
    }

    /**
     * Count the total quantity of a specific item across all slots.
     */
    public int count(int itemId) {
        return api.queryInventoryItems(InventoryFilter.builder()
                .inventoryId(id)
                .itemId(itemId)
                .nonEmpty(true)
                .build()).stream()
                .mapToInt(InventoryItem::quantity)
                .sum();
    }

    /**
     * Get the number of occupied (non-empty) slots.
     */
    public int occupiedSlots() {
        return getItems().size();
    }

    /**
     * Check if the inventory contains an item whose name contains the given string (case-insensitive).
     * Uses {@link com.botwithus.bot.api.GameAPI#getItemType} to resolve item names.
     *
     * @param name the name substring to search for
     * @return {@code true} if a matching item is found
     */
    public boolean contains(String name) {
        return getItems().stream()
                .anyMatch(item -> {
                    var type = api.getItemType(item.itemId());
                    return type != null && type.name() != null
                            && type.name().toLowerCase().contains(name.toLowerCase());
                });
    }

    /**
     * Count the total quantity of items whose name contains the given string (case-insensitive).
     *
     * @param name the name substring to search for
     * @return the total quantity of matching items
     */
    public int count(String name) {
        return getItems().stream()
                .filter(item -> {
                    var type = api.getItemType(item.itemId());
                    return type != null && type.name() != null
                            && type.name().toLowerCase().contains(name.toLowerCase());
                })
                .mapToInt(InventoryItem::quantity)
                .sum();
    }

    /**
     * Get the number of free (empty) slots.
     */
    public int freeSlots() {
        return (int) getAllSlots().stream().filter(i -> i.itemId() == -1).count();
    }
}
