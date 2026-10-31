package com.botwithus.bot.api.model;

/**
 * One slot of a player's applied appearance: either an identity kit (body part) or an item, never
 * both.
 *
 * @param kit  identity-kit id, or {@link #NONE} when the slot shows an item
 * @param item item id shown in the slot, or {@link #NONE} when it shows a kit
 */
public record AppearanceSlot(int kit, int item) {

    /** The half of a slot that is not in use. */
    public static final int NONE = -1;

    /** True when this slot shows an item. */
    public boolean hasItem() {
        return item != NONE;
    }

    /** True when this slot shows an identity kit. */
    public boolean hasKit() {
        return kit != NONE;
    }
}
