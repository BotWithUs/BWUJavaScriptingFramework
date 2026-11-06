package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.ItemType;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Rich wrapper around {@link GroundItemInfo} with lazy {@link ItemType}
 * resolution and option-keyed {@link #interact(String) interact}.
 *
 * <p>Obtained through {@link GroundItems#query()} — don't construct
 * directly.</p>
 */
public final class GroundItem implements EntityContext {

    private final GameAPI api;
    private final GroundItemInfo raw;
    private final IntFunction<ItemType> typeLookup;
    private ItemType cachedType;

    GroundItem(GameAPI api, GroundItemInfo raw, IntFunction<ItemType> typeLookup) {
        this.api = api;
        this.raw = raw;
        this.typeLookup = typeLookup;
    }

    public GroundItemInfo raw() { return raw; }

    public int handle()    { return raw.handle(); }
    public int itemId()    { return raw.itemId(); }
    public int quantity()  { return raw.quantity(); }

    public String name() {
        ItemType t = getType();
        return t == null ? null : t.name();
    }

    @Override public int tileX() { return raw.tileX(); }
    @Override public int tileY() { return raw.tileY(); }
    @Override public int plane() { return raw.plane(); }

    public ItemType getType() {
        if (cachedType == null) {
            cachedType = typeLookup.apply(itemId());
        }
        return cachedType;
    }

    public List<String> getOptions() {
        ItemType t = getType();
        return t == null ? List.of() : t.groundOptions();
    }

    public boolean hasOption(String option) {
        for (String o : getOptions()) {
            if (o != null && o.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queue an action by 1-based right-click option index.
     *
     * <p>The action names the stack the way the game's own menu entry does:
     * {@code param1} is the item id, {@code param2}/{@code param3} are the
     * stack's absolute world tile X/Y, exactly as the snapshot row reports
     * them. The server matches a take against the item <em>and</em> the tile,
     * so an action without the tile is dispatched and then ignored.</p>
     *
     * <p>The action carries no plane: the client resolves the tile on the
     * local player's plane, so a stack on another floor is not taken.</p>
     *
     * @throws IllegalArgumentException for {@code optionIndex} outside [1, 6]
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.GROUND_ITEM_OPTIONS.length) {
            throw new IllegalArgumentException("Ground item option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(
                ActionTypes.GROUND_ITEM_OPTIONS[optionIndex],
                itemId(), tileX(), tileY()));
    }

    /**
     * Queue an action by option text, matched case-insensitively against the
     * item type's ground options. Sends the same {@code (itemId, tileX, tileY)}
     * shape as {@link #interact(int)}. False if the option isn't on the menu.
     */
    public boolean interact(String option) {
        List<String> opts = getOptions();
        for (int i = 0; i < opts.size(); ++i) {
            String o = opts.get(i);
            if (o != null && o.equalsIgnoreCase(option)) {
                interact(i + 1);
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "GroundItem{" + name() + " id=" + itemId() + " x" + quantity()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
