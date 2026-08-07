package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.InventoryItem;

/**
 * The local player's worn equipment (inv id 94, equipment iface 1464).
 * Singleton per {@link GameAPI}; obtain via {@code api.equipment()}.
 *
 * <p>Equipment slots have fixed semantic meaning (head, body, legs, ...)
 * unlike a free-form inventory grid. The {@link InventoryContainer} read
 * methods still work over the raw slot index; the {@link Slot} enum exposes
 * the semantic names. Slot indices are the game-engine values (the equipment
 * inv has gaps at index 6, 8, 11, 15, 16 — those are not real slots).</p>
 */
public final class Equipment extends InventoryContainer {

    public static final int INVENTORY_ID = 94;
    public static final int INTERFACE_ID = 1464;
    public static final int COMPONENT_ID = 15;

    public Equipment(GameAPI api) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID);
    }

    /** Item worn in the given semantic slot, or {@code null} when nothing is equipped there. */
    public InventoryItem getSlot(Slot slot) {
        return getSlot(slot.index);
    }

    /**
     * Click the given semantic slot with a 1-based right-click option index.
     * Option indices are stable per slot and verifiable in-game via the
     * mini-menu (right-click → count from the top, starting at 1).
     */
    public boolean interact(Slot slot, int optionIndex) {
        return interact(slot.index, optionIndex);
    }

    /**
     * Click the given semantic slot with the named right-click option.
     *
     * <p><b>Caveat</b>: option lookup goes through {@code ItemType.inventoryOptions()}
     * because the producer-side {@code getComponentOptions(iface, comp)} RPC
     * was removed in the Slice 3 cull. Equipment-context options that
     * <i>aren't</i> in the inventory-option list (e.g. {@code "Teleport"} on a
     * worn slayer cape, {@code "Castle Wars"} on a ring of duelling)
     * therefore won't resolve here — {@link #interact(Slot, int)} with a
     * verified option index is the working path for those.</p>
     */
    public boolean interact(Slot slot, String option) {
        InventoryItem item = getSlot(slot);
        if (item == null || item.isEmpty()) {
            return false;
        }
        return interactFirst(item.itemId(), option);
    }

    /** Convenience: try the standard unequip options ("Remove" / "Unequip") on the slot. */
    public boolean unequip(Slot slot) {
        if (interact(slot, "Remove")) return true;
        return interact(slot, "Unequip");
    }

    /** True when {@code itemId} is in any equipment slot. */
    public boolean isEquipped(int itemId) {
        return contains(itemId);
    }

    /**
     * Semantic equipment slot names paired with their in-game inventory indices.
     * Indices match the game engine's equipment-inv layout — there are gaps
     * (6 / 8 / 11 / 15 / 16) where the layout reserves space for slots that
     * don't have a wearable category.
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

        public final int index;

        Slot(int index) {
            this.index = index;
        }

        /** Look up by index; {@code null} when {@code index} doesn't map to a real slot. */
        public static Slot fromIndex(int index) {
            for (Slot s : values()) {
                if (s.index == index) return s;
            }
            return null;
        }
    }
}
