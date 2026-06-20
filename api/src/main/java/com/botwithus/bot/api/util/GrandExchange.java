package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.GrandExchangeOffer;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;

/**
 * Convenience wrapper around the Grand Exchange interface (iface 105).
 *
 * <p>Restored after the Slice 3 cull. Two sides:</p>
 * <ul>
 *   <li><b>State reads</b> ({@link #getOffers()}, {@link #findOffer},
 *       {@link #hasFreeSlot()}, {@link #allCompleted()}) — currently stubbed.
 *       The pre-rewrite GE class read the offers through
 *       {@code GameAPI.getGrandExchangeOffers()}, an RPC the producer hasn't
 *       re-exposed yet. Once it returns (or per-slot varbit reads land),
 *       the stubs below switch from {@code List.of()} to the real materialisation.
 *       Component scraping of iface 105 is a viable interim path if needed
 *       — each slot's item id and progress are reflected on visible child
 *       components.</li>
 *   <li><b>Interface state + mutations</b> ({@link #isOpen()},
 *       {@link #collectAll()}, {@link #abortOffer(int)}) — work today
 *       through the existing {@link GameSnapshot#isInterfaceOpen} probe and
 *       {@code queue_action} clicks on iface 105 components. Component IDs
 *       for the mutation buttons need MCP verification; the constants
 *       below carry a {@code TODO} note where so.</li>
 * </ul>
 */
public final class GrandExchange {

    /** GE interface id. */
    public static final int INTERFACE_ID = Interfaces.GRAND_EXCHANGE;

    /** Offer slot is empty. */
    public static final int STATUS_EMPTY = 0;
    /** Offer is actively buying. */
    public static final int STATUS_BUYING = 2;
    /** Offer is actively selling. */
    public static final int STATUS_SELLING = 3;
    /** Buy offer has completed. */
    public static final int STATUS_BUY_COMPLETE = 5;
    /** Sell offer has completed. */
    public static final int STATUS_SELL_COMPLETE = 6;

    /** "Collect all" / "Collect-notes / Collect-items" button. TODO: verify component id via MCP. */
    private static final int COLLECT_BUTTON_COMPONENT = 38;
    /** Per-slot button base on the GE summary panel. TODO: verify component id + per-slot stride via MCP. */
    private static final int SLOT_BUTTON_BASE_COMPONENT = 7;
    /** Abort-offer button on an opened slot's detail panel. TODO: verify component id via MCP. */
    private static final int ABORT_BUTTON_COMPONENT = 196;

    private final GameAPI api;

    public GrandExchange(GameAPI api) {
        this.api = api;
    }

    // ---------------------------------------------------------------- State

    /** True when the GE interface is open. */
    public boolean isOpen() {
        GameSnapshot snap = api.snapshot();
        return snap != null && snap.isInterfaceOpen(INTERFACE_ID);
    }

    /**
     * All offer slots.
     *
     * <p>Currently returns {@link List#of()} — the producer-side
     * {@code query_grand_exchange} RPC was removed in the Slice 3 cull and
     * hasn't been re-added. Once it lands, this delegates to it; until then,
     * call sites should expect an empty list.</p>
     */
    public List<GrandExchangeOffer> getOffers() {
        // TODO(producer): wire `api.queryGrandExchangeOffers()` once the RPC is
        // re-added, OR scrape per-slot state off iface 105 components, OR
        // read offer varbits directly. Until any of those land, this is empty.
        return List.of();
    }

    /** First non-empty offer matching {@code itemId}, or {@code null}. */
    public GrandExchangeOffer findOffer(int itemId) {
        for (GrandExchangeOffer o : getOffers()) {
            if (o.itemId() == itemId && !o.isEmpty()) {
                return o;
            }
        }
        return null;
    }

    /** True when at least one slot is empty. */
    public boolean hasFreeSlot() {
        for (GrandExchangeOffer o : getOffers()) {
            if (o.isEmpty()) return true;
        }
        return false;
    }

    /** True when every active (non-empty) slot is in a completed state. */
    public boolean allCompleted() {
        for (GrandExchangeOffer o : getOffers()) {
            if (o.isEmpty()) continue;
            if (o.status() != STATUS_BUY_COMPLETE && o.status() != STATUS_SELL_COMPLETE) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Mutations

    /** Click "Collect all" on the GE summary panel. */
    public boolean collectAll() {
        if (!isOpen()) return false;
        return queueButton(COLLECT_BUTTON_COMPONENT, 1, -1);
    }

    /**
     * Open the detail panel for {@code slot} (0-based) and click "Abort offer".
     * No-op when the GE isn't open or the slot is empty.
     *
     * <p>Implemented as two component clicks: open the slot, then click abort.
     * Component ids for the per-slot button + abort target need MCP
     * verification — see TODO markers on the constants.</p>
     */
    public boolean abortOffer(int slot) {
        if (!isOpen() || slot < 0 || slot > 7) return false;
        // Open the slot first.
        if (!queueButton(SLOT_BUTTON_BASE_COMPONENT + slot, 1, -1)) return false;
        // Then click abort.
        return queueButton(ABORT_BUTTON_COMPONENT, 1, -1);
    }

    /**
     * Click an arbitrary GE component by id with the given right-click option.
     * Escape hatch for callers that know the exact button id; prefer the
     * named helpers above where they exist.
     */
    public boolean queueComponentClick(int componentId, int optionIndex) {
        if (!isOpen()) return false;
        return queueButton(componentId, optionIndex, -1);
    }

    // ---------------------------------------------------------------- Statics

    /** Remaining quantity on an offer (helper that mirrors {@link GrandExchangeOffer#remaining()}). */
    public static int getRemainingQuantity(GrandExchangeOffer offer) {
        return offer.remaining();
    }

    /** Completion fraction (helper that mirrors {@link GrandExchangeOffer#completionFraction()}). */
    public static double getCompletionFraction(GrandExchangeOffer offer) {
        return offer.completionFraction();
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * Queue a plain (non-slot) button click. Wire shape mirrors
     * {@link ComponentNode#interact(int)}: param2 = sub-index (-1 when none),
     * param3 = packed (iface{@code <<}16)|comp hash.
     */
    private boolean queueButton(int componentId, int optionIndex, int subIndex) {
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT,
                optionIndex,
                subIndex,
                Interfaces.componentHash(INTERFACE_ID, componentId)));
        return true;
    }
}
