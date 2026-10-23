package com.botwithus.bot.api.model;

import com.botwithus.bot.api.inventory.Equipment;

import java.util.List;
import java.util.Map;

/**
 * Item definition from the game cache.
 *
 * <p>An item can claim up to three equipment slots. {@code wearpos} is the slot it is worn
 * in; {@code wearpos2} and {@code wearpos3} are further slots the item blocks while worn.
 * A two-handed weapon is the common case: it is worn in the weapon slot and blocks the
 * shield slot through {@code wearpos2} (cache item 36021, Camel staff, carries wearpos 3 and
 * wearpos2 5, while the one-handed Dragon mace, item 1434, carries wearpos 3 and wearpos2
 * -1). See {@link #isTwoHanded()}.</p>
 *
 * @param id               the item ID
 * @param name             the item display name
 * @param members          whether this is a members-only item
 * @param stackable        whether this item stacks in inventory
 * @param shopPrice        the base shop price
 * @param geBuyLimit       the Grand Exchange buy limit
 * @param category         the item category ID
 * @param notedId          the noted variant ID, or {@code -1} if none
 * @param wearpos          the equipment slot this item occupies, or {@code -1} if not wearable
 * @param wearpos2         a second equipment slot this item blocks while worn, or
 *                         {@link #NO_WEARPOS} if none
 * @param wearpos3         a third equipment slot this item blocks while worn, or
 *                         {@link #NO_WEARPOS} if none
 * @param exchangeable     whether this item can be traded on the Grand Exchange
 * @param groundOptions    the right-click options when the item is on the ground
 * @param inventoryOptions the right-click options when the item is in inventory
 * @param params           additional key-value parameters from the item definition
 * @see com.botwithus.bot.api.GameAPI#getItemType
 */
public record ItemType(
        int id,
        String name,
        boolean members,
        boolean stackable,
        int shopPrice,
        int geBuyLimit,
        int category,
        int notedId,
        int wearpos,
        int wearpos2,
        int wearpos3,
        boolean exchangeable,
        List<String> groundOptions,
        List<String> inventoryOptions,
        Map<String, Object> params
) {

    /** The cache's value for a wear position the item does not claim. */
    public static final int NO_WEARPOS = -1;

    /**
     * Builds a definition that claims no extra wear positions; {@code wearpos2} and
     * {@code wearpos3} are {@link #NO_WEARPOS}. Kept so code written before the extra wear
     * positions were exposed still compiles and links.
     *
     * @param id               the item ID
     * @param name             the item display name
     * @param members          whether this is a members-only item
     * @param stackable        whether this item stacks in inventory
     * @param shopPrice        the base shop price
     * @param geBuyLimit       the Grand Exchange buy limit
     * @param category         the item category ID
     * @param notedId          the noted variant ID, or {@code -1} if none
     * @param wearpos          the equipment slot this item occupies, or {@code -1}
     * @param exchangeable     whether this item can be traded on the Grand Exchange
     * @param groundOptions    the right-click options when the item is on the ground
     * @param inventoryOptions the right-click options when the item is in inventory
     * @param params           additional key-value parameters from the item definition
     */
    public ItemType(int id, String name, boolean members, boolean stackable, int shopPrice,
                    int geBuyLimit, int category, int notedId, int wearpos, boolean exchangeable,
                    List<String> groundOptions, List<String> inventoryOptions,
                    Map<String, Object> params) {
        this(id, name, members, stackable, shopPrice, geBuyLimit, category, notedId, wearpos,
                NO_WEARPOS, NO_WEARPOS, exchangeable, groundOptions, inventoryOptions, params);
    }

    /**
     * Whether wearing this item also blocks the shield slot, so nothing can be worn in the
     * off hand alongside it. True for two-handed weapons (Dragon 2h sword, Staff of light,
     * Noxious scythe, Camel staff), false for one-handed ones (Abyssal whip, longswords,
     * maces).
     *
     * @return {@code true} if {@code wearpos2} or {@code wearpos3} is the shield slot
     */
    public boolean isTwoHanded() {
        int shield = Equipment.Slot.SHIELD.index;
        return wearpos2 == shield || wearpos3 == shield;
    }
}
