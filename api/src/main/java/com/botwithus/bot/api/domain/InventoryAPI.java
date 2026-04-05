package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.InventoryFilter;

import java.util.List;

/**
 * Inventory querying, item lookups, and config type definitions.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface InventoryAPI {

    // ============================== Inventory & Items ==============================

    /**
     * Queries all available inventories (backpack, bank, equipment, etc.).
     *
     * @return a list of inventory info records
     */
    List<InventoryInfo> queryInventories();

    /**
     * Queries inventory items matching the given filter.
     *
     * @param filter the inventory filter criteria
     * @return a list of matching inventory items
     * @see InventoryFilter
     */
    List<InventoryItem> queryInventoryItems(InventoryFilter filter);

    /**
     * Returns the item in a specific inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return the inventory item, or an empty item if the slot is unoccupied
     */
    InventoryItem getInventoryItem(int inventoryId, int slot);

    /**
     * Returns the custom variables attached to an item in an inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return a list of item variables
     */
    List<ItemVar> getItemVars(int inventoryId, int slot);

    /**
     * Returns a specific variable value for an item in an inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @param varId       the variable ID
     * @return the variable value
     */
    int getItemVarValue(int inventoryId, int slot, int varId);

    /**
     * Checks whether an inventory slot contains a valid item.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return {@code true} if the slot contains a valid item
     */
    boolean isInventoryItemValid(int inventoryId, int slot);

    // ============================== Config Type Lookups ==============================

    /**
     * Returns an item definition from the game cache.
     *
     * @param id the item ID
     * @return the item type definition
     */
    ItemType getItemType(int id);

    /**
     * Returns an NPC definition from the game cache.
     *
     * @param id the NPC type ID
     * @return the NPC type definition
     */
    NpcType getNpcType(int id);

    /**
     * Returns a location (game object) definition from the game cache.
     *
     * @param id the location type ID
     * @return the location type definition
     */
    LocationType getLocationType(int id);

    /**
     * Returns an enum (key-value mapping) definition from the game cache.
     *
     * @param id the enum type ID
     * @return the enum type definition
     */
    EnumType getEnumType(int id);

    /**
     * Returns a struct definition from the game cache.
     *
     * @param id the struct type ID
     * @return the struct type definition
     */
    StructType getStructType(int id);

    /**
     * Returns an animation sequence definition from the game cache.
     *
     * @param id the sequence type ID
     * @return the sequence type definition
     */
    SequenceType getSequenceType(int id);

    /**
     * Returns a quest definition from the game cache.
     *
     * @param id the quest type ID
     * @return the quest type definition
     */
    QuestType getQuestType(int id);
}
