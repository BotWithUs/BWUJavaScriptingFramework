package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;

/**
 * The bank inventory (inv id 95). The bank UI lives at iface 517 with a
 * separate component for the bank slot grid. Singleton per {@link GameAPI};
 * obtain via {@code api.bank()}.
 *
 * <p>Inherits read/containment/slot interactions from {@link InventoryContainer}.
 * The high-level verbs ({@link #depositAll()}, {@link #withdraw}, presets, ...)
 * queue {@code COMPONENT} actions against iface 517.</p>
 *
 * <p>Every interface, component and variable the verbs touch is addressed by its
 * gameval name through {@link GameAPI#gamevals()}, so a game renumber is fixed by
 * refreshing the gameval index rather than by editing this class. The literal
 * ids are only the fallback for a host with no index deployed; they were checked
 * against the gameval table, the CS2 menu-op layout and the V1 bank API.</p>
 *
 * <p>Item-slot menus on both the banked-items grid and the bank's backpack grid
 * share one op layout: {@code 1} default quantity, {@code 2} one, {@code 3} five,
 * {@code 4} ten, {@code 5} last X, {@code 6} enter X, {@code 7} all.</p>
 */
public final class Bank extends InventoryContainer {

    public static final int INVENTORY_ID = 95;
    public static final int INTERFACE_ID = 517;
    /**
     * Banked-items slot grid ({@code BANK__BANK_INV}). The slot interactions
     * inherited from {@link InventoryContainer} bind this literal; the verbs on
     * this class resolve it by name. Component 195 is the grid's scrollbar.
     */
    public static final int COMPONENT_ID = 201;
    /** Backpack items shown inside the bank interface ({@code BANK__INVENTORY_CLICK}). */
    public static final int BACKPACK_COMPONENT = 15;
    /** Withdraw-X / Deposit-X input layer ({@code MESLAYER}). */
    public static final int INPUT_INTERFACE = 1469;

    private static final GamevalRef BANK_INTERFACE =
            new GamevalRef(GamevalType.INTERFACE, "BANK", INTERFACE_ID);
    private static final GamevalRef INPUT_LAYER =
            new GamevalRef(GamevalType.INTERFACE, "MESLAYER", INPUT_INTERFACE);

    private static final GamevalRef ITEMS = component("BANK__BANK_INV", COMPONENT_ID);
    private static final GamevalRef BACKPACK_ITEMS = component("BANK__INVENTORY_CLICK", BACKPACK_COMPONENT);
    private static final GamevalRef CLOSE_BUTTON = component("BANK__CLOSE_BUTTON_LAYER", 317);
    private static final GamevalRef DEPOSIT_BACKPACK_BUTTON = component("BANK__BANK_INV_BUTTON", 39);
    private static final GamevalRef DEPOSIT_WORN_BUTTON = component("BANK__BANK_WORN_BUTTON", 42);
    private static final GamevalRef DEPOSIT_FAMILIAR_BUTTON = component("BANK__BANK_BOB_BUTTON", 45);
    private static final GamevalRef DEPOSIT_POUCH_BUTTON = component("BANK__BANK_POUCH_BUTTON", 48);
    /** Quick-preset buttons; the sub-index selects a preset on the current page, or flips the page. */
    private static final GamevalRef PRESET_BUTTONS = component("BANK__SHARE_QUICK_CLICK", 119);
    private static final GamevalRef MODE_ONE_BUTTON = component("BANK__DEFAULT_OP_1", 93);
    private static final GamevalRef MODE_FIVE_BUTTON = component("BANK__DEFAULT_OP_5", 96);
    private static final GamevalRef MODE_TEN_BUTTON = component("BANK__DEFAULT_OP_10", 99);
    private static final GamevalRef MODE_ALL_BUTTON = component("BANK__DEFAULT_OP_ALL_BUTTON", 103);
    private static final GamevalRef MODE_CUSTOM_BUTTON = component("BANK__DEFAULT_OP_X", 106);

    private static final GamevalRef VARBIT_TRANSFER_MODE =
            new GamevalRef(GamevalType.VARBIT, "BANK_CUSTOMOP", 45189);
    private static final GamevalRef VARBIT_SIDE_VIEW =
            new GamevalRef(GamevalType.VARBIT, "BANK_CURRENT_INV", 45139);
    private static final GamevalRef VARBIT_SIDE_TAB =
            new GamevalRef(GamevalType.VARBIT, "BANK_SIDE_TAB_SELECTED", 45191);
    private static final GamevalRef VARBIT_PRESET_PAGE =
            new GamevalRef(GamevalType.VARBIT, "SHARE_PRESET_BANK_BUTTON_PAGE", 49662);
    private static final GamevalRef VARP_WITHDRAW_AMOUNT =
            new GamevalRef(GamevalType.VARP, "BANK_MOVE_X", 111);
    private static final GamevalRef VARP_WITHDRAW_AS_NOTE =
            new GamevalRef(GamevalType.VARP, "BANKCERT", 160);

    /** Left-click op on a plain button. */
    private static final int OP_CLICK = 1;
    /** Item-slot menu op that opens the enter-amount dialog. */
    private static final int OP_ENTER_AMOUNT = 6;
    /** Sub-index for a plain button with no sub-component selection. */
    private static final int NO_SUB_INDEX = -1;
    /** Component of the input layer that receives the typed amount. */
    private static final int INPUT_COMPONENT = 0;

    /** Highest preset number the quick-preset buttons reach. */
    private static final int MAX_PRESET = 18;
    /** Presets shown per quick-preset page. */
    private static final int PRESETS_PER_PAGE = 9;
    /** Preset page holding presets 1-9. */
    private static final int FIRST_PRESET_PAGE = 0;
    /** Preset page holding presets 10-18. */
    private static final int SECOND_PRESET_PAGE = 1;
    /** Sub-index on the quick-preset buttons that flips between the two pages. */
    private static final int PRESET_PAGE_SWITCH_SUB_INDEX = 100;

    /** {@code BANK_CURRENT_INV} value while the side panel shows the backpack. */
    private static final int SIDE_VIEW_BACKPACK = 0;
    /** {@code BANK_CURRENT_INV} value while the side panel shows worn equipment. */
    private static final int SIDE_VIEW_EQUIPMENT = 2;
    /** {@code BANK_SIDE_TAB_SELECTED} value while the presets tab is showing. */
    private static final int SIDE_TAB_PRESETS = 1;
    /** {@code BANKCERT} value while withdrawing as notes. */
    private static final int WITHDRAW_AS_NOTE = 1;

    public Bank(GameAPI api) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID);
    }

    // ---------------------------------------------------------------- State

    /** True when the bank UI (iface 517) is open. */
    public boolean isOpen() {
        return isInterfaceOpen(BANK_INTERFACE);
    }

    /** True when the withdraw-X / deposit-X dialog is up. */
    public boolean isInputOpen() {
        return isInterfaceOpen(INPUT_LAYER);
    }

    /** Currently configured custom withdraw amount. */
    public int getWithdrawAmount() {
        return api.getVarp(VARP_WITHDRAW_AMOUNT.resolve(api.gamevals()));
    }

    /** Preset page (0 for presets 1-9, 1 for 10-18). */
    public int getPresetPage() {
        return varbit(VARBIT_PRESET_PAGE);
    }

    /** Side-panel view alongside the bank grid. */
    public SideView view() {
        int value = varbit(VARBIT_SIDE_VIEW);
        if (value == SIDE_VIEW_BACKPACK) {
            return SideView.BACKPACK;
        }
        return value == SIDE_VIEW_EQUIPMENT ? SideView.EQUIPMENT : SideView.FAMILIAR;
    }

    /** Withdraw mode — items as-is or as bank notes. */
    public WithdrawMode withdrawMode() {
        int value = api.getVarp(VARP_WITHDRAW_AS_NOTE.resolve(api.gamevals()));
        return value == WITHDRAW_AS_NOTE ? WithdrawMode.NOTE : WithdrawMode.ITEM;
    }

    /** Bank interface setting — standard transfer or preset selector. */
    public BankSetting setting() {
        return varbit(VARBIT_SIDE_TAB) == SIDE_TAB_PRESETS ? BankSetting.PRESETS : BankSetting.TRANSFER;
    }

    /** Currently selected transfer mode (1 / 5 / 10 / custom / all). */
    public TransferAmount transferMode() {
        int value = varbit(VARBIT_TRANSFER_MODE);
        for (TransferAmount amount : TransferAmount.values()) {
            if (amount.varbitValue == value) {
                return amount;
            }
        }
        return TransferAmount.ONE;
    }

    // ---------------------------------------------------------------- Close

    /** Click the bank's close (X) button. No-op when the bank isn't open. */
    public boolean close() {
        if (!isOpen()) {
            return true;
        }
        return queueButton(CLOSE_BUTTON, NO_SUB_INDEX);
    }

    // ---------------------------------------------------------------- Deposits

    /** Deposit carried backpack contents. */
    public boolean depositAll() {
        return clickWhenOpen(DEPOSIT_BACKPACK_BUTTON);
    }

    /** Deposit worn equipment. */
    public boolean depositEquipment() {
        return clickWhenOpen(DEPOSIT_WORN_BUTTON);
    }

    /** Deposit familiar inventory. */
    public boolean depositFamiliar() {
        return clickWhenOpen(DEPOSIT_FAMILIAR_BUTTON);
    }

    /** Deposit coin pouch. */
    public boolean depositCoins() {
        return clickWhenOpen(DEPOSIT_POUCH_BUTTON);
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
     * Returns false when the bank isn't open or the item isn't in the backpack.
     */
    public boolean deposit(int itemId, TransferAmount amount) {
        return clickBackpackItem(itemId, amount.menuOp);
    }

    /** Open the deposit-X dialog for {@code itemId}. */
    public boolean startDepositX(int itemId) {
        return clickBackpackItem(itemId, OP_ENTER_AMOUNT);
    }

    /** Finish a deposit-X or withdraw-X by submitting the amount. */
    public boolean finishTransferX(int amount) {
        if (!isOpen() || !isInputOpen()) {
            return false;
        }
        api.fireKeyTrigger(INPUT_LAYER.resolve(api.gamevals()), INPUT_COMPONENT, String.valueOf(amount));
        return true;
    }

    // ---------------------------------------------------------------- Withdraws

    /** Withdraw one stack of {@code itemId} with the given transfer amount. */
    public boolean withdraw(int itemId, TransferAmount amount) {
        return clickBankItem(itemId, amount.menuOp);
    }

    /** Withdraw the entire stack of {@code itemId}. */
    public boolean withdrawAll(int itemId) {
        return withdraw(itemId, TransferAmount.ALL);
    }

    /** Open the withdraw-X dialog for {@code itemId}. */
    public boolean startWithdrawX(int itemId) {
        return clickBankItem(itemId, OP_ENTER_AMOUNT);
    }

    // ---------------------------------------------------------------- Presets

    /**
     * Withdraw a saved bank preset (1-18) through the quick-preset buttons,
     * flipping to the preset's page first when needed (presets 10-18 live on
     * the second page). The caller is responsible for waiting for the bank to
     * drain — this method only queues the clicks.
     */
    public boolean withdrawPreset(int presetNumber) {
        if (!isOpen() || presetNumber < 1 || presetNumber > MAX_PRESET) {
            return false;
        }
        int targetPage = presetNumber > PRESETS_PER_PAGE ? SECOND_PRESET_PAGE : FIRST_PRESET_PAGE;
        if (getPresetPage() != targetPage) {
            queueButton(PRESET_BUTTONS, PRESET_PAGE_SWITCH_SUB_INDEX);
        }
        int slotOnPage = (presetNumber - 1) % PRESETS_PER_PAGE + 1;
        return queueButton(PRESET_BUTTONS, slotOnPage);
    }

    // ---------------------------------------------------------------- Transfer mode

    /** Select the bank's quantity-mode button. */
    public boolean setTransferMode(TransferAmount mode) {
        if (!isOpen()) {
            return false;
        }
        GamevalRef button = switch (mode) {
            case ONE -> MODE_ONE_BUTTON;
            case FIVE -> MODE_FIVE_BUTTON;
            case TEN -> MODE_TEN_BUTTON;
            case ALL -> MODE_ALL_BUTTON;
            case CUSTOM -> MODE_CUSTOM_BUTTON;
        };
        return queueButton(button, NO_SUB_INDEX);
    }

    // ---------------------------------------------------------------- Helpers

    /** A component gameval whose fallback is {@code componentId} within the bank interface. */
    private static GamevalRef component(String gameval, int componentId) {
        return new GamevalRef(GamevalType.COMPONENT, gameval,
                Interfaces.componentHash(INTERFACE_ID, componentId));
    }

    private boolean isInterfaceOpen(GamevalRef iface) {
        GameSnapshot snap = api.snapshot();
        return snap != null && snap.isInterfaceOpen(iface.resolve(api.gamevals()));
    }

    private int varbit(GamevalRef ref) {
        return api.getVarbit(ref.resolve(api.gamevals()));
    }

    private boolean clickWhenOpen(GamevalRef button) {
        if (!isOpen()) {
            return false;
        }
        return queueButton(button, NO_SUB_INDEX);
    }

    /** Click the banked stack of {@code itemId} with the item-slot menu op {@code op}. */
    private boolean clickBankItem(int itemId, int op) {
        if (!isOpen()) {
            return false;
        }
        return queueSlot(ITEMS, getFirst(itemId), op);
    }

    /** Click the carried stack of {@code itemId} in the bank's backpack grid with op {@code op}. */
    private boolean clickBackpackItem(int itemId, int op) {
        if (!isOpen()) {
            return false;
        }
        return queueSlot(BACKPACK_ITEMS, api.backpack().getFirst(itemId), op);
    }

    /**
     * Queue an item-slot click: op in param1, the slot in param2, the grid's
     * packed hash in param3. {@code false} when {@code item} is absent.
     */
    private boolean queueSlot(GamevalRef grid, InventoryItem item, int op) {
        if (item == null) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op, item.slot(),
                grid.resolve(api.gamevals())));
        return true;
    }

    /**
     * Queue a left-click on a plain (non-slot) interface button. Wire shape
     * matches {@link com.botwithus.bot.api.component.ComponentNode#interact(int)}:
     * param2 carries an optional sub-index ({@link #NO_SUB_INDEX} for most
     * buttons), param3 the packed {@code (iface<<16)|comp} hash.
     */
    private boolean queueButton(GamevalRef button, int subIndex) {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_CLICK, subIndex,
                button.resolve(api.gamevals())));
        return true;
    }

    // ---------------------------------------------------------------- Enums

    /**
     * Bank transfer quantity, with its {@code BANK_CUSTOMOP} varbit value and its
     * op on the item-slot menus.
     */
    public enum TransferAmount {
        ONE(2, 2),
        FIVE(3, 3),
        TEN(4, 4),
        ALL(7, 7),
        CUSTOM(5, 5);

        private final int varbitValue;
        private final int menuOp;

        TransferAmount(int varbitValue, int menuOp) {
            this.varbitValue = varbitValue;
            this.menuOp = menuOp;
        }
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
