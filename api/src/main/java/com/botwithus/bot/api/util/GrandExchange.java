package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;

/**
 * Convenience wrapper around the Grand Exchange interface ({@code STOCKMARKET},
 * iface 105) and its collect-all panel ({@code STOCKMARKET_COLLECTALL}, iface 651).
 *
 * <p>Only the interface probe and the button clicks live here. Offer state
 * (per-slot item, status, progress) is not exposed yet: the producer RPC that
 * used to publish it was removed and nothing replaces it, so there is no read
 * that could answer truthfully.</p>
 *
 * <p>Every interface and component is addressed by its gameval name through
 * {@link GameAPI#gamevals()}; the literal ids are the fallback for a host with
 * no gameval index deployed.</p>
 */
public final class GrandExchange {

    /** GE interface id. */
    public static final int INTERFACE_ID = Interfaces.GRAND_EXCHANGE;
    /** Collect-all panel interface id. */
    public static final int COLLECT_INTERFACE_ID = 651;
    /** Number of offer slots. */
    public static final int SLOT_COUNT = 8;

    private static final GamevalRef GE_INTERFACE =
            new GamevalRef(GamevalType.INTERFACE, "STOCKMARKET", INTERFACE_ID);
    private static final GamevalRef COLLECT_TO_INVENTORY = new GamevalRef(GamevalType.COMPONENT,
            "STOCKMARKET_COLLECTALL__SEND_TO_INV_ACTIVE_LAYER",
            Interfaces.componentHash(COLLECT_INTERFACE_ID, 6));
    private static final GamevalRef COLLECT_TO_BANK = new GamevalRef(GamevalType.COMPONENT,
            "STOCKMARKET_COLLECTALL__SEND_TO_BANK_ACTIVE_LAYER",
            Interfaces.componentHash(COLLECT_INTERFACE_ID, 14));
    /** Per-slot abort buttons on the offer summary, indexed by 0-based slot. */
    private static final List<GamevalRef> ABORT_BUTTONS = List.of(
            abortButton(0, 27),
            abortButton(1, 48),
            abortButton(2, 69),
            abortButton(3, 93),
            abortButton(4, 117),
            abortButton(5, 141),
            abortButton(6, 165),
            abortButton(7, 189));

    /** Left-click op on a plain button. */
    private static final int OP_CLICK = 1;
    /** Sub-index for a plain button with no sub-component selection. */
    private static final int NO_SUB_INDEX = -1;

    private final GameAPI api;

    public GrandExchange(GameAPI api) {
        this.api = api;
    }

    // ---------------------------------------------------------------- State

    /** True when the GE interface is open. */
    public boolean isOpen() {
        GameSnapshot snap = api.snapshot();
        return snap != null && snap.isInterfaceOpen(GE_INTERFACE.resolve(api.gamevals()));
    }

    // ---------------------------------------------------------------- Mutations

    /** Click "Collect to inventory" on the collect-all panel. No-op when the GE isn't open. */
    public boolean collectAll() {
        return clickWhenOpen(COLLECT_TO_INVENTORY);
    }

    /** Click "Collect to bank" on the collect-all panel. No-op when the GE isn't open. */
    public boolean collectToBank() {
        return clickWhenOpen(COLLECT_TO_BANK);
    }

    /**
     * Click the abort button of offer {@code slot} (0-based) on the offer
     * summary. No-op when the GE isn't open or {@code slot} is out of range.
     */
    public boolean abortOffer(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return false;
        }
        return clickWhenOpen(ABORT_BUTTONS.get(slot));
    }

    /**
     * Click an arbitrary GE component by id with the given right-click option.
     * Escape hatch for callers that know the exact button id; prefer the
     * named helpers above where they exist.
     */
    public boolean queueComponentClick(int componentId, int optionIndex) {
        if (!isOpen()) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, optionIndex, NO_SUB_INDEX,
                Interfaces.componentHash(GE_INTERFACE.resolve(api.gamevals()), componentId)));
        return true;
    }

    // ---------------------------------------------------------------- Helpers

    /** The {@code STOCKMARKET__ABORT<slot>} button, falling back to {@code componentId}. */
    private static GamevalRef abortButton(int slot, int componentId) {
        return new GamevalRef(GamevalType.COMPONENT, "STOCKMARKET__ABORT" + slot,
                Interfaces.componentHash(INTERFACE_ID, componentId));
    }

    /**
     * Queue a left-click on a plain button when the GE is open. Wire shape
     * mirrors {@link com.botwithus.bot.api.component.ComponentNode#interact(int)}:
     * param2 = sub-index, param3 = packed {@code (iface<<16)|comp} hash.
     */
    private boolean clickWhenOpen(GamevalRef button) {
        if (!isOpen()) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_CLICK, NO_SUB_INDEX,
                button.resolve(api.gamevals())));
        return true;
    }
}
