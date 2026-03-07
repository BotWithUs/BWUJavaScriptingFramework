package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Provides access to the player's worn equipment (inventory ID 94, interface 1464).
 *
 * <p>Supports querying equipped items and interacting with equipment slots
 * (e.g., removing items, checking charges).</p>
 *
 * @see Slot
 * @see InventoryContainer
 */
public final class Equipment {

    /** RS3 equipment inventory ID. */
    public static final int INVENTORY_ID = 94;
    /** RS3 equipment interface ID. */
    public static final int INTERFACE_ID = 1464;
    /** RS3 equipment component ID within the interface. */
    public static final int COMPONENT_ID = 15;

    private final GameAPI api;
    private final InventoryContainer container;

    /**
     * Creates a new equipment wrapper.
     *
     * @param api the game API instance
     */
    public Equipment(GameAPI api) {
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
     * Checks if the specified item is currently equipped.
     *
     * @param itemId the item ID to look for
     * @return {@code true} if the item is equipped
     */
    public boolean contains(int itemId) {
        return container.contains(itemId);
    }

    /**
     * Returns all currently equipped items.
     *
     * @return a list of equipped inventory items
     */
    public List<InventoryItem> getItems() {
        return container.getItems();
    }

    /**
     * Get the item in a specific equipment slot.
     */
    public InventoryItem getSlot(Slot slot) {
        return container.slot(slot.getIndex());
    }

    // ========================== Interaction Methods ==========================

    /**
     * Interact with an equipment slot by slot and option string.
     *
     * @param slot   the equipment slot
     * @param option the right-click option (e.g. "Remove", "Check")
     * @return true if the action was queued
     */
    public boolean interact(Slot slot, String option) {
        Component comp = findComponentBySubIndex(slot.getIndex());
        if (comp == null) return false;
        return interactComponent(comp, option);
    }

    /**
     * Interact with an equipment slot by slot and option index (1-based).
     */
    public boolean interact(Slot slot, int optionIndex) {
        Component comp = findComponentBySubIndex(slot.getIndex());
        if (comp == null) return false;
        return queueComponentAction(comp, optionIndex);
    }

    /**
     * Interact with an equipped item by item ID and option string.
     */
    public boolean interact(int itemId, String option) {
        Component comp = findComponentByItem(itemId);
        if (comp == null) return false;
        return interactComponent(comp, option);
    }

    /**
     * Attempts to equip an item from the backpack by trying common equip options.
     * Tries "Wear", "Wield", and "Equip" in order.
     *
     * @param itemId   the item ID to equip
     * @param backpack the backpack instance to interact with
     * @return true if an equip action was queued
     */
    public boolean equipFromBackpack(int itemId, Backpack backpack) {
        for (String option : new String[]{"Wear", "Wield", "Equip"}) {
            if (backpack.interact(itemId, option)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unequips an item from the given equipment slot.
     *
     * @param slot the equipment slot to unequip
     * @return true if the action was queued
     */
    public boolean unequip(Slot slot) {
        return interact(slot, "Remove");
    }

    // ========================== Helpers ==========================

    private Component findComponentBySubIndex(int subIndex) {
        // Equipment items are sub-components of the main equipment component
        List<Component> children = api.getComponentChildren(INTERFACE_ID, COMPONENT_ID);
        return children.stream()
                .filter(c -> c.subComponentId() == subIndex)
                .findFirst().orElse(null);
    }

    private Component findComponentByItem(int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .itemId(itemId)
                .build());
        return comps.isEmpty() ? null : comps.getFirst();
    }

    private boolean interactComponent(Component comp, String option) {
        List<String> options = api.getComponentOptions(comp.interfaceId(), comp.componentId());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(option)) {
                return queueComponentAction(comp, i + 1);
            }
        }
        return false;
    }

    private boolean queueComponentAction(Component comp, int optionIndex) {
        int hash = comp.interfaceId() << 16 | comp.componentId();
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT, optionIndex, comp.subComponentId(), hash));
        return true;
    }

    // ========================== Equipment Slots ==========================

    /**
     * Equipment slot positions.
     * Each slot corresponds to a body part where items can be worn.
     */
    public enum Slot {
        HEAD(0),
        CAPE(1),
        NECK(2),
        WEAPON(3),
        BODY(4),
        SHIELD(5),
        LEGS(7),
        HANDS(9),
        FEET(10),
        RING(12),
        AMMUNITION(13),
        AURA(14),
        POCKET(17);

        private final int index;

        Slot(int index) {
            this.index = index;
        }

        private static final Slot[] SLOTS = values();

        /**
         * Resolves an equipment slot by its index.
         *
         * @param index the slot index
         * @return the matching {@link Slot}, or {@code null} if no slot has that index
         */
        public static Slot resolve(int index) {
            return Arrays.stream(SLOTS)
                    .filter(slot -> slot.index == index)
                    .findFirst().orElse(null);
        }

        /**
         * Returns the numeric slot index used by the game engine.
         *
         * @return the slot index
         */
        public int getIndex() {
            return this.index;
        }
    }
}
