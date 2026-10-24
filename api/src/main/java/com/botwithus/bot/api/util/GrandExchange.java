package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;
import java.util.stream.IntStream;

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
    /** {@code STOCKMARKET_COLLECTALL__SEND_TO_INV}: the "To Backpack" button. */
    private static final int SEND_TO_INV_COMPONENT = 0;
    /** {@code STOCKMARKET_COLLECTALL__SEND_TO_BANK}: the "To Bank" button. */
    private static final int SEND_TO_BANK_COMPONENT = 1;
    /** {@code STOCKMARKET__ABORT0}: offer slot 0's abort button. */
    private static final int FIRST_ABORT_COMPONENT = 25;
    /** Components per offer-slot summary; slot n's abort button is {@code FIRST_ABORT_COMPONENT + n * this}. */
    private static final int OFFER_SLOT_STRIDE = 14;

    /*
     * The fallback ids are the live layout, the one the names resolve to in an
     * index built from the live cache. Jagex rebuilt both interfaces over the
     * summer: 651 went from 22 components to 6, so the old *_ACTIVE_LAYER
     * buttons (651:6 and 651:14) no longer exist, and 105's abort buttons moved
     * from 27, 48, 69, ... to 25 + 14 per slot. Package-private so the test can
     * pin them without a whole GameAPI fake.
     */
    static final GamevalRef COLLECT_TO_INVENTORY = new GamevalRef(GamevalType.COMPONENT,
            "STOCKMARKET_COLLECTALL__SEND_TO_INV",
            Interfaces.componentHash(COLLECT_INTERFACE_ID, SEND_TO_INV_COMPONENT));
    static final GamevalRef COLLECT_TO_BANK = new GamevalRef(GamevalType.COMPONENT,
            "STOCKMARKET_COLLECTALL__SEND_TO_BANK",
            Interfaces.componentHash(COLLECT_INTERFACE_ID, SEND_TO_BANK_COMPONENT));
    /** Per-slot abort buttons on the offer summary, indexed by 0-based slot. */
    static final List<GamevalRef> ABORT_BUTTONS = IntStream.range(0, SLOT_COUNT)
            .mapToObj(GrandExchange::abortButton)
            .toList();

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
     *
     * <p>The button is "Abort Offer" only while the offer runs. Once the offer
     * has finished, the game relabels the same button "Collect and repeat", and
     * this click then does that instead. Check the slot first if it matters.</p>
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

    /** The {@code STOCKMARKET__ABORT<slot>} button, falling back to its live id. */
    private static GamevalRef abortButton(int slot) {
        return new GamevalRef(GamevalType.COMPONENT, "STOCKMARKET__ABORT" + slot,
                Interfaces.componentHash(INTERFACE_ID, FIRST_ABORT_COMPONENT + slot * OFFER_SLOT_STRIDE));
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
