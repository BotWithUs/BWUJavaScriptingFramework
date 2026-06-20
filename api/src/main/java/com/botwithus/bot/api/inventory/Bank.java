package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.util.Interfaces;

/**
 * The bank inventory (inv id 95). The bank UI lives at iface 517 with a
 * separate component for the bank slot grid. Singleton per {@link GameAPI};
 * obtain via {@code api.bank()}.
 *
 * <p>Inherits read/containment/slot interactions from {@link InventoryContainer}.
 * The high-level verbs ({@link #depositAll()}, {@link #withdraw}, presets, ...)
 * are restored on top of the new component query facade — they click known
 * iface 517 buttons by id where possible, or fall back to text-based lookup
 * over {@code api.components().in(INTERFACE_ID)}.</p>
 *
 * <p>Component IDs (HASH_*, *_COMPONENT) were ported verbatim from the
 * pre-rewrite {@code Bank.java} (commit {@code c5e33dc}) and re-verified
 * against the live bank interface where used. They drift across game
 * updates — if a verb stops working, re-query via MCP {@code query_components}
 * against iface 517 and update the constant here.</p>
 */
public final class Bank extends InventoryContainer {

    public static final int INVENTORY_ID = 95;
    public static final int INTERFACE_ID = 517;
    /** Bank item grid component. */
    public static final int COMPONENT_ID = 195;
    /** Backpack items shown inside the bank interface. */
    public static final int BACKPACK_COMPONENT = 15;
    /** Withdraw-X / Deposit-X input dialog interface. */
    public static final int INPUT_INTERFACE = 1469;
    /** Close (X) button. Components 86 / 310 also advertise "Close" but are inert overlays. */
    private static final int CLOSE_COMPONENT = 317;
    /** Presets-mode toggle. */
    private static final int PRESETS_BUTTON_COMPONENT = 177;
    /** Preset grid component — sub-components 1-10 are preset slots. */
    private static final int PRESET_COMPONENT = 119;
    /** Custom-amount edit toggle. */
    private static final int CUSTOM_AMOUNT_COMPONENT = 98;
    /** Transfer-mode quantity buttons (varbit option). */
    private static final int MODE_ONE_COMPONENT = 93;
    private static final int MODE_FIVE_COMPONENT = 96;
    private static final int MODE_TEN_COMPONENT = 99;
    private static final int MODE_ALL_COMPONENT = 103;
    private static final int MODE_CUSTOM_COMPONENT = 106;

    // ---- Varbits / varcs / varps backing read-state ----
    private static final int VARBIT_HIDDEN_OPTION = 45189;
    private static final int VARBIT_SIDE_VIEW = 45139;
    private static final int VARBIT_BANK_SETTING = 45191;
    private static final int VARBIT_PRESET_PAGE = 49662;
    private static final int VARC_CUSTOM_INPUT_STATE = 2873;
    private static final int VARC_CUSTOM_INPUT_TYPE = 2236;
    private static final int VARP_WITHDRAW_AMOUNT = 111;
    private static final int VARP_WITHDRAW_MODE = 160;

    public Bank(GameAPI api) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID);
    }

    // ---------------------------------------------------------------- State

    /** True when the bank UI (iface 517) is open. */
    public boolean isOpen() {
        GameSnapshot snap = api.snapshot();
        return snap != null && snap.isInterfaceOpen(INTERFACE_ID);
    }

    /** True when the withdraw-X / deposit-X dialog is up. */
    public boolean isInputOpen() {
        GameSnapshot snap = api.snapshot();
        return snap != null && snap.isInterfaceOpen(INPUT_INTERFACE);
    }

    /** True when the bank is in mid-custom-amount editing. */
    public boolean isEditingCustomAmount() {
        return api.getVarcInt(VARC_CUSTOM_INPUT_STATE) == 11
                && api.getVarcInt(VARC_CUSTOM_INPUT_TYPE) == 7;
    }

    /** Currently configured custom withdraw amount. */
    public int getWithdrawAmount() {
        return api.getVarp(VARP_WITHDRAW_AMOUNT);
    }

    /** Preset page (0 for presets 1-9, 1 for 10-18). */
    public int getPresetPage() {
        return api.getVarbit(VARBIT_PRESET_PAGE);
    }

    /** Side-panel view alongside the bank grid. */
    public SideView view() {
        return switch (api.getVarbit(VARBIT_SIDE_VIEW)) {
            case 0 -> SideView.BACKPACK;
            case 2 -> SideView.EQUIPMENT;
            default -> SideView.FAMILIAR;
        };
    }

    /** Withdraw mode — items as-is or as bank notes. */
    public WithdrawMode withdrawMode() {
        return api.getVarp(VARP_WITHDRAW_MODE) == 1 ? WithdrawMode.NOTE : WithdrawMode.ITEM;
    }

    /** Bank interface setting — standard transfer or preset selector. */
    public BankSetting setting() {
        return api.getVarbit(VARBIT_BANK_SETTING) == 1 ? BankSetting.PRESETS : BankSetting.TRANSFER;
    }

    /** Currently selected transfer mode (1 / 5 / 10 / custom / all). */
    public TransferAmount transferMode() {
        return switch (api.getVarbit(VARBIT_HIDDEN_OPTION)) {
            case 3 -> TransferAmount.FIVE;
            case 4 -> TransferAmount.TEN;
            case 5 -> TransferAmount.CUSTOM;
            case 7 -> TransferAmount.ALL;
            default -> TransferAmount.ONE;
        };
    }

    // ---------------------------------------------------------------- Close

    /** Click the bank's close (X) button. No-op when the bank isn't open. */
    public boolean close() {
        if (!isOpen()) return true;
        queueButton(CLOSE_COMPONENT, 1, -1);
        return true;
    }

    // ---------------------------------------------------------------- Deposits

    /** Deposit carried backpack contents. */
    public boolean depositAll() {
        return clickByText("Deposit Inventory");
    }

    /** Deposit worn equipment. */
    public boolean depositEquipment() {
        return clickByText("Deposit Equipment");
    }

    /** Deposit familiar inventory. */
    public boolean depositFamiliar() {
        return clickByText("Deposit Familiar");
    }

    /** Deposit coin pouch. */
    public boolean depositCoins() {
        return clickByText("Deposit Money pouch");
    }

    /** Deposit carried items + equipment + familiar + coins, in order. */
    public boolean depositEverything() {
        boolean any = false;
        any |= depositAll();
        any |= depositEquipment();
        any |= depositFamiliar();
        any |= depositCoins();
        return any;
    }

    /**
     * Deposit one stack of {@code itemId} from the backpack-inside-bank panel.
     * Returns false when the bank isn't open, the item isn't in the backpack,
     * or the option index can't be mapped.
     */
    public boolean deposit(int itemId, TransferAmount amount) {
        if (!isOpen()) return false;
        ComponentNode comp = findBackpackItem(itemId);
        if (comp == null) return false;
        int idx = mapDepositOption(amount);
        if (idx < 1) return false;
        comp.interact(idx);
        return true;
    }

    /** Open the deposit-X dialog for {@code itemId}. */
    public boolean startDepositX(int itemId) {
        if (!isOpen()) return false;
        ComponentNode comp = findBackpackItem(itemId);
        if (comp == null) return false;
        comp.interact(6);
        return true;
    }

    /** Finish a deposit-X or withdraw-X by submitting the amount. */
    public boolean finishTransferX(int amount) {
        if (!isOpen() || !isInputOpen()) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ---------------------------------------------------------------- Withdraws

    /** Withdraw one stack of {@code itemId} with the given transfer amount. */
    public boolean withdraw(int itemId, TransferAmount amount) {
        if (!isOpen() || !contains(itemId)) return false;
        ComponentNode comp = findBankItem(itemId);
        if (comp == null) return false;
        int idx = mapWithdrawOption(amount);
        if (idx < 1) return false;
        comp.interact(idx);
        return true;
    }

    /** Withdraw the entire stack of {@code itemId}. */
    public boolean withdrawAll(int itemId) {
        return withdraw(itemId, TransferAmount.ALL);
    }

    /** Open the withdraw-X dialog for {@code itemId}. */
    public boolean startWithdrawX(int itemId) {
        if (!isOpen() || !contains(itemId)) return false;
        ComponentNode comp = findBankItem(itemId);
        if (comp == null) return false;
        comp.interact(6);
        return true;
    }

    // ---------------------------------------------------------------- Presets

    /**
     * Withdraw a saved bank preset (1-18). Handles page switching
     * (presets 10-18 live on page 1). The caller is responsible for waiting
     * for the bank to drain — this method only queues the click.
     */
    public boolean withdrawPreset(int presetNumber) {
        if (!isOpen() || presetNumber < 1 || presetNumber > 18) return false;

        if (setting() != BankSetting.PRESETS) {
            queueButton(PRESETS_BUTTON_COMPONENT, 1, -1);
        }

        int targetPage = presetNumber > 9 ? 1 : 0;
        if (getPresetPage() != targetPage) {
            queueButton(PRESET_COMPONENT, 1, 100);
        }

        int preset = presetNumber > 9 ? presetNumber - 9 : presetNumber;
        queueButton(PRESET_COMPONENT, 1, preset);
        return true;
    }

    // ---------------------------------------------------------------- Transfer mode

    /** Select the bank's quantity-mode button. */
    public boolean setTransferMode(TransferAmount mode) {
        if (!isOpen()) return false;
        // Preserved verbatim from the pre-rewrite Bank: each mode has its
        // own (componentId, optionIndex) pair — option index isn't always 1.
        return switch (mode) {
            case ONE    -> queueButton(MODE_ONE_COMPONENT,    1, -1);
            case FIVE   -> queueButton(MODE_FIVE_COMPONENT,   2, -1);
            case TEN    -> queueButton(MODE_TEN_COMPONENT,    3, -1);
            case ALL    -> queueButton(MODE_ALL_COMPONENT,    4, -1);
            case CUSTOM -> queueButton(MODE_CUSTOM_COMPONENT, 5, -1);
        };
    }

    /** Click the custom-amount edit toggle. No-op if already editing. */
    public boolean startCustomAmount() {
        if (!isOpen()) return false;
        if (isEditingCustomAmount()) return true;
        queueButton(CUSTOM_AMOUNT_COMPONENT, 1, -1);
        return true;
    }

    /** Submit the custom amount value (after {@link #startCustomAmount}). */
    public boolean finishCustomAmount(int amount) {
        if (!isOpen() || !isEditingCustomAmount()) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ---------------------------------------------------------------- Helpers

    private ComponentNode findBankItem(int itemId) {
        return api.components()
                .under(INTERFACE_ID, COMPONENT_ID)
                .withItemId(itemId)
                .first();
    }

    private ComponentNode findBackpackItem(int itemId) {
        return api.components()
                .under(INTERFACE_ID, BACKPACK_COMPONENT)
                .withItemId(itemId)
                .first();
    }

    /**
     * Find a button on the bank interface by visible label and click its
     * primary option. Used by deposit verbs whose component ids drift across
     * game updates — label-text matching is steadier than hardcoded ids,
     * but if a deposit button is unlabeled the search returns null and the
     * verb is a no-op.
     */
    private boolean clickByText(String label) {
        if (!isOpen()) return false;
        ComponentNode button = api.components()
                .in(INTERFACE_ID)
                .containingText(label)
                .visible()
                .first();
        if (button == null) return false;
        button.interact(1);
        return true;
    }

    /**
     * Queue a click on a plain (non-slot) interface button. Wire shape matches
     * what {@link ComponentNode#interact(int)} uses: param2 carries an optional
     * sub-index (-1 when the button has no sub-component selection — most
     * buttons), and param3 carries the packed (iface{@code <<}16)|comp hash.
     * Inventory slot grids use a different shape (see {@link InventoryContainer#interact}).
     */
    private boolean queueButton(int componentId, int optionIndex, int subIndex) {
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT,
                optionIndex,
                subIndex,
                Interfaces.componentHash(INTERFACE_ID, componentId)));
        return true;
    }

    private static int mapDepositOption(TransferAmount amount) {
        return switch (amount) {
            case ONE -> 2;
            case FIVE -> 3;
            case TEN -> 4;
            case CUSTOM -> 5;
            case ALL -> 7;
            default -> -1;
        };
    }

    private static int mapWithdrawOption(TransferAmount amount) {
        return switch (amount) {
            case ONE -> 1;
            case FIVE -> 3;
            case TEN -> 4;
            case CUSTOM -> 5;
            case ALL -> 7;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- Enums

    /** Bank transfer quantity preset. */
    public enum TransferAmount {
        ONE, FIVE, TEN, ALL, CUSTOM
    }

    /** Bank withdraw mode: as item or as bank note. */
    public enum WithdrawMode {
        ITEM, NOTE
    }

    /** Side panel view alongside the bank grid. */
    public enum SideView {
        BACKPACK, EQUIPMENT, FAMILIAR
    }

    /** Bank interface mode — standard transfer controls or preset selector. */
    public enum BankSetting {
        TRANSFER, PRESETS
    }
}
