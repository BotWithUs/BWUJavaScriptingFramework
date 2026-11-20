package com.botwithus.bot.api.dialog;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;

import java.util.Optional;

/**
 * The "Are you sure you want to destroy this item?" dialog ({@code CONFIRM_DESTROY_V2}, 1183)
 * the game opens when an item's Destroy option is chosen. Its buttons are driven with the
 * {@code DIALOGUE} action, as a manual click on them sends, which is what v1 and xapi sent.
 *
 * <pre>{@code
 * if (api.backpack().interact("Broken pot", "Destroy")
 *         && Timing.delayUntil(() -> ConfirmItemDestruction.isOpen(api), 2_000)) {
 *     ConfirmItemDestruction.confirm(api);
 * }
 * }</pre>
 */
public final class ConfirmItemDestruction {

    private static final int INTERFACE_ID = 1183;
    private static final GamevalRef INTERFACE = new GamevalRef(GamevalType.INTERFACE,
            "CONFIRM_DESTROY_V2", INTERFACE_ID);
    private static final GamevalRef YES = new GamevalRef(GamevalType.COMPONENT,
            "CONFIRM_DESTROY_V2__BUTTON_YES", Interfaces.componentHash(INTERFACE_ID, 7));
    private static final GamevalRef NO = new GamevalRef(GamevalType.COMPONENT,
            "CONFIRM_DESTROY_V2__BUTTON_NO", Interfaces.componentHash(INTERFACE_ID, 8));
    private static final GamevalRef ITEM_NAME = new GamevalRef(GamevalType.COMPONENT,
            "CONFIRM_DESTROY_V2__DESTROY_NAME", Interfaces.componentHash(INTERFACE_ID, 4));

    /** param1 a dialogue button carries. */
    private static final int DIALOGUE_PARAM1 = 0;
    /** param2 a dialogue button carries: no sub-slot. */
    private static final int NO_SUB_SLOT = -1;

    private ConfirmItemDestruction() {}

    /** True when the destroy confirmation is open. Snapshot read: no round-trip. */
    public static boolean isOpen(GameAPI api) {
        return api.isInterfaceOpen(INTERFACE.resolve(api.gamevals()));
    }

    /**
     * The name of the item the dialog is about, as the dialog shows it. One round-trip.
     *
     * @return the name, or empty when the dialog is not open
     */
    public static Optional<String> itemName(GameAPI api) {
        if (!isOpen(api)) {
            return Optional.empty();
        }
        int hash = ITEM_NAME.resolve(api.gamevals());
        Component name = api.getComponent(Interfaces.interfaceIdFromHash(hash),
                Interfaces.componentIdFromHash(hash));
        return name == null ? Optional.empty() : Optional.of(Dialog.plain(name.text()));
    }

    /**
     * Presses Yes: the item is destroyed.
     *
     * @return {@code true} when the dialog was open and the press was queued
     */
    public static boolean confirm(GameAPI api) {
        return press(api, YES);
    }

    /**
     * Presses No: the item is kept.
     *
     * @return {@code true} when the dialog was open and the press was queued
     */
    public static boolean cancel(GameAPI api) {
        return press(api, NO);
    }

    private static boolean press(GameAPI api, GamevalRef button) {
        if (!isOpen(api)) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.DIALOGUE, DIALOGUE_PARAM1, NO_SUB_SLOT,
                button.resolve(api.gamevals())));
        return true;
    }
}
