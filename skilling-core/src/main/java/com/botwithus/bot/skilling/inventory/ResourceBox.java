package com.botwithus.bot.skilling.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalInt;

/**
 * A "resource box" carried in the backpack that stores gathered resources to
 * extend a gather trip — the Woodcutting wood box, the Mining ore box, and the
 * like. Generic and game-agnostic: it knows only the ordered set of box tier item
 * ids and the resource item ids it stores, so the same helper serves any gathering
 * skill.
 *
 * <p>Box interactions are ordinary backpack item clicks (the right-click "Fill" /
 * "Empty" options), routed through {@link Backpack#interactFirst(int, String)}.
 * The box item definitions expose <b>no</b> inventory options in the cache (the
 * box menu is driven by a client script), so the string-option lookup can miss; a
 * caller-supplied raw option index is used as the fallback. When neither resolves,
 * {@link #fill()} / {@link #emptyAtBank()} return {@code false} and the caller
 * degrades to plain banking.</p>
 *
 * <p>The box's live fill count is per-item {@code ObjVarDomain} state not present
 * in the snapshot, so this helper deliberately does <b>not</b> expose it. Callers
 * detect "box full" behaviourally — a {@link #fill()} that frees no backpack slots
 * means the box is full.</p>
 */
public final class ResourceBox {

    private static final Logger log = LoggerFactory.getLogger(ResourceBox.class);

    /** No raw fallback index — only the named option is attempted. */
    public static final int NO_FALLBACK = -1;

    private final Backpack backpack;
    private final Bank bank;
    private final int[] tierIds;     // ordered low → high tier
    private final int[] storableIds; // resource items the box stores
    private final int fillFallbackIndex;
    private final int emptyFallbackIndex;

    /**
     * @param api                game API (the backpack facade is taken from it)
     * @param tierIds            box item ids, ordered <b>low → high</b> tier; the
     *                           highest-tier box present in the pack is used
     * @param storableIds        resource item ids the box stores (for loose counts)
     * @param fillFallbackIndex  raw 1-based "Fill" option index, or {@link #NO_FALLBACK}
     * @param emptyFallbackIndex raw 1-based "Empty" option index, or {@link #NO_FALLBACK}
     */
    public ResourceBox(GameAPI api, int[] tierIds, int[] storableIds,
                       int fillFallbackIndex, int emptyFallbackIndex) {
        this.backpack = api.backpack();
        this.bank = api.bank();
        this.tierIds = tierIds.clone();
        this.storableIds = storableIds.clone();
        this.fillFallbackIndex = fillFallbackIndex;
        this.emptyFallbackIndex = emptyFallbackIndex;
    }

    /** The highest-tier box item id currently in the backpack, or empty when none. */
    public OptionalInt presentTier() {
        int found = -1;
        for (int id : tierIds) {
            if (backpack.contains(id)) {
                found = id; // tierIds is low→high, so the last match is the highest tier
            }
        }
        return found < 0 ? OptionalInt.empty() : OptionalInt.of(found);
    }

    /** Whether any tier of the box is in the backpack. */
    public boolean isPresent() {
        return presentTier().isPresent();
    }

    /** The highest-tier box item id currently in the (open) bank, or empty when none. */
    public OptionalInt bankedTier() {
        int found = -1;
        for (int id : tierIds) {
            if (bank.contains(id)) {
                found = id; // tierIds is low→high, so the last match is the highest tier
            }
        }
        return found < 0 ? OptionalInt.empty() : OptionalInt.of(found);
    }

    /** Whether any tier of the box is sitting in the open bank — e.g. deposited last trip. */
    public boolean isInBank() {
        return bankedTier().isPresent();
    }

    /** Total loose (un-boxed) quantity of the stored resources in the backpack. */
    public int looseCount() {
        int n = 0;
        for (int id : storableIds) {
            n += backpack.count(id);
        }
        return n;
    }

    /** Queue the box's "Fill" — move loose resources into the box. */
    public boolean fill() {
        return act("Fill", fillFallbackIndex);
    }

    /** Queue the box's "Empty" — only meaningful with the bank open. */
    public boolean emptyAtBank() {
        return act("Empty", emptyFallbackIndex);
    }

    private boolean act(String option, int fallbackIndex) {
        OptionalInt tier = presentTier();
        if (tier.isEmpty()) {
            return false;
        }
        int boxId = tier.getAsInt();
        if (backpack.interactFirst(boxId, option)) {
            return true;
        }
        if (fallbackIndex > 0 && backpack.interactFirst(boxId, fallbackIndex)) {
            return true;
        }
        log.debug("Resource box {} exposed no '{}' option (fallback index {})", boxId, option, fallbackIndex);
        return false;
    }
}
