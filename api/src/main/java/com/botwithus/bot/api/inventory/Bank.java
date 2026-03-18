package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.List;

import static com.botwithus.bot.api.inventory.ComponentHelper.componentHash;
import static com.botwithus.bot.api.inventory.ComponentHelper.queueComponentAction;

/**
 * Provides access to the bank interface (inventory ID 95, interface 517).
 * Ported from the legacy BotWithUs API to use the pipe RPC.
 *
 * <p>Interaction methods queue game actions through the pipe. Varbit/varc checks
 * use the RPC variable-reading methods.</p>
 */
public final class Bank {

    public static final int INVENTORY_ID = 95;
    public static final int INTERFACE_ID = 517;
    /** Bank item grid component. */
    public static final int BANK_COMPONENT = 195;
    /** Backpack items shown inside the bank interface. */
    public static final int BACKPACK_COMPONENT = 15;
    /** Withdraw-X / Deposit-X input dialog interface. */
    public static final int INPUT_INTERFACE = 1469;

    // Varbit IDs used by the bank interface
    private static final int VARBIT_HIDDEN_OPTION = 45189;
    private static final int VARBIT_SIDE_VIEW = 45139;
    private static final int VARBIT_BANK_SETTING = 45191;
    private static final int VARBIT_PRESET_PAGE = 49662;

    // Varc IDs
    private static final int VARC_CUSTOM_INPUT_STATE = 2873;
    private static final int VARC_CUSTOM_INPUT_TYPE = 2236;

    // Varp IDs
    private static final int VARP_WITHDRAW_AMOUNT = 111;
    private static final int VARP_WITHDRAW_MODE = 160;

    // Component hashes for specific bank buttons
    private static final int HASH_PRESETS_BUTTON = INTERFACE_ID << 16 | 177;
    /** Preset grid component — sub-components 1-10 are preset slots. */
    private static final int PRESET_COMPONENT = 119;

    private final GameAPI api;
    private final InventoryContainer container;

    /**
     * Creates a new bank wrapper.
     *
     * @param api the game API instance
     */
    public Bank(GameAPI api) {
        this.api = api;
        this.container = new InventoryContainer(api, INVENTORY_ID);
    }

    /**
     * Returns the underlying {@link InventoryContainer} for advanced queries.
     *
     * @return the inventory container
     */
    public InventoryContainer container() {
        return container;
    }

    // ========================== State Queries ==========================

    /**
     * Check if the bank interface is open.
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE_ID);
    }

    /**
     * Check if the withdraw/deposit X input dialog is open.
     */
    public boolean isInputOpen() {
        return api.isInterfaceOpen(INPUT_INTERFACE);
    }

    /**
     * Check if the player is currently editing a custom amount.
     */
    public boolean isEditingCustomAmount() {
        return api.getVarcInt(VARC_CUSTOM_INPUT_STATE) == 11
                && api.getVarcInt(VARC_CUSTOM_INPUT_TYPE) == 7;
    }

    /**
     * Checks if the bank contains the specified item.
     *
     * @param itemId the item ID to look for
     * @return {@code true} if the item is in the bank
     */
    public boolean contains(int itemId) {
        return container.contains(itemId);
    }

    /**
     * Checks if the bank contains at least the specified amount of an item.
     *
     * @param itemId the item ID to look for
     * @param amount the minimum quantity required
     * @return {@code true} if enough of the item is in the bank
     */
    public boolean contains(int itemId, int amount) {
        return container.contains(itemId, amount);
    }

    /**
     * Checks if the bank contains an item whose name contains the given string (case-insensitive).
     *
     * @param name the name substring to search for
     * @return {@code true} if a matching item is in the bank
     */
    public boolean contains(String name) {
        return container.contains(name);
    }

    /**
     * Counts the total quantity of an item in the bank.
     *
     * @param itemId the item ID to count
     * @return the total quantity
     */
    public int count(int itemId) {
        return container.count(itemId);
    }

    /**
     * Counts the total quantity of items whose name contains the given string (case-insensitive).
     *
     * @param name the name substring to search for
     * @return the total quantity of matching items
     */
    public int count(String name) {
        return container.count(name);
    }

    /**
     * Returns all non-empty items in the bank.
     *
     * @return a list of bank items
     */
    public List<InventoryItem> getItems() {
        return container.getItems();
    }

    // ========================== Deposit Methods ==========================

    /**
     * Deposit all carried items.
     */
    public boolean depositAll() {
        if (!isOpen()) return false;
        return interactByOption("Deposit carried items");
    }

    /**
     * Deposit all worn equipment.
     */
    public boolean depositEquipment() {
        if (!isOpen()) return false;
        return interactByOption("Deposit worn items");
    }

    /**
     * Deposit familiar inventory.
     */
    public boolean depositFamiliar() {
        if (!isOpen()) return false;
        return interactByOption("Deposit familiar items");
    }

    /**
     * Deposit coin pouch.
     */
    public boolean depositCoins() {
        if (!isOpen()) return false;
        return interactByOption("Deposit coin pouch");
    }

    /**
     * Deposit everything (carried items, equipment, familiar, coins).
     */
    public boolean depositEverything() {
        return depositAll() && depositEquipment() && depositFamiliar() && depositCoins();
    }

    /**
     * Deposit an item by ID with the specified transfer amount.
     */
    public boolean deposit(int itemId, TransferAmount amount) {
        if (!isOpen() || !container.contains(itemId)) return false;
        Component comp = findBackpackItem(itemId);
        if (comp == null) return false;
        int optionIndex = mapDepositOption(amount);
        if (optionIndex < 0) return false;
        return queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Start a deposit-X interaction for an item (opens the input dialog).
     */
    public boolean startDepositX(int itemId) {
        if (!isOpen() || !container.contains(itemId)) return false;
        Component comp = findBackpackItem(itemId);
        if (comp == null) return false;
        return queueComponentAction(api, comp, 6);
    }

    /**
     * Finish a deposit/withdraw-X by entering the amount.
     * Call after {@link #startDepositX} or {@link #startWithdrawX} when the input dialog is open.
     */
    public boolean finishTransferX(int amount) {
        if (!isOpen() || !api.isInterfaceOpen(INPUT_INTERFACE)) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ========================== Withdraw Methods ==========================

    /**
     * Withdraw an item by ID with the specified transfer amount.
     */
    public boolean withdraw(int itemId, TransferAmount amount) {
        if (!isOpen() || !container.contains(itemId)) return false;
        Component comp = findBankItem(itemId);
        if (comp == null) return false;
        int optionIndex = mapWithdrawOption(amount);
        if (optionIndex < 0) return false;
        return queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Withdraw all of an item by ID.
     */
    public boolean withdrawAll(int itemId) {
        return withdraw(itemId, TransferAmount.ALL);
    }

    /**
     * Start a withdraw-X interaction (opens the input dialog).
     */
    public boolean startWithdrawX(int itemId) {
        if (!isOpen() || !container.contains(itemId)) return false;
        Component comp = findBankItem(itemId);
        if (comp == null) return false;
        return queueComponentAction(api, comp, 6);
    }

    // ========================== Presets ==========================

    /**
     * Withdraw a bank preset by number (1-18).
     * Handles page switching if needed (presets 1-9 on page 0, 10-18 on page 1).
     */
    public boolean withdrawPreset(int presetNumber) {
        if (!isOpen() || presetNumber < 1 || presetNumber > 18) return false;

        // Switch to presets mode if not already there
        if (setting() != BankSetting.PRESETS) {
            api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_PRESETS_BUTTON));
            // Caller should wait for the setting to change
        }

        // Handle page switching
        int targetPage = presetNumber > 9 ? 1 : 0;
        if (getPresetPage() != targetPage) {
            api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 100, INTERFACE_ID << 16 | PRESET_COMPONENT));
        }

        int preset = presetNumber > 9 ? presetNumber - 9 : presetNumber;
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, preset, INTERFACE_ID << 16 | PRESET_COMPONENT));
        return true;
    }

    // ========================== Transfer Mode ==========================

    /**
     * Get the current transfer mode (1, 5, 10, custom, all).
     */
    public TransferAmount transferMode() {
        return switch (api.getVarbit(VARBIT_HIDDEN_OPTION)) {
            case 3 -> TransferAmount.FIVE;
            case 4 -> TransferAmount.TEN;
            case 5 -> TransferAmount.CUSTOM;
            case 7 -> TransferAmount.ALL;
            default -> TransferAmount.ONE;
        };
    }

    /**
     * Set the transfer mode for subsequent bank operations.
     */
    public boolean setTransferMode(TransferAmount mode) {
        if (!isOpen()) return false;
        return switch (mode) {
            case ONE -> queueRawAction(1, -1, INTERFACE_ID << 16 | 93);
            case FIVE -> queueRawAction(2, -1, INTERFACE_ID << 16 | 96);
            case TEN -> queueRawAction(3, -1, INTERFACE_ID << 16 | 99);
            case ALL -> queueRawAction(4, -1, INTERFACE_ID << 16 | 103);
            case CUSTOM -> queueRawAction(5, -1, INTERFACE_ID << 16 | 106);
            default -> false;
        };
    }

    /**
     * Start editing the custom withdraw amount.
     */
    public boolean startCustomAmount() {
        if (!isOpen()) return false;
        if (isEditingCustomAmount()) return true;
        // Click the custom amount button (517 << 16 | 98)
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, INTERFACE_ID << 16 | 98));
        return true;
    }

    /**
     * Finish editing the custom amount by entering a value.
     */
    public boolean finishCustomAmount(int amount) {
        if (!isOpen() || !isEditingCustomAmount()) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ========================== Bank State ==========================

    /**
     * Returns the currently configured custom withdraw amount.
     *
     * @return the withdraw amount
     */
    public int getWithdrawAmount() {
        return api.getVarp(VARP_WITHDRAW_AMOUNT);
    }

    /**
     * Returns the currently selected preset page (0 for presets 1-9, 1 for presets 10-18).
     *
     * @return the preset page index
     */
    public int getPresetPage() {
        return api.getVarbit(VARBIT_PRESET_PAGE);
    }

    /**
     * Returns the currently selected side panel view in the bank interface.
     *
     * @return the side view (backpack, equipment, or familiar)
     */
    public SideView view() {
        return switch (api.getVarbit(VARBIT_SIDE_VIEW)) {
            case 0 -> SideView.BACKPACK;
            case 2 -> SideView.EQUIPMENT;
            default -> SideView.FAMILIAR;
        };
    }

    /**
     * Returns the current withdraw mode (item or noted form).
     *
     * @return the withdraw mode
     */
    public WithdrawMode withdrawMode() {
        return switch (api.getVarp(VARP_WITHDRAW_MODE)) {
            case 1 -> WithdrawMode.NOTE;
            default -> WithdrawMode.ITEM;
        };
    }

    /**
     * Returns the current bank interface setting (transfer or presets mode).
     *
     * @return the bank setting
     */
    public BankSetting setting() {
        return switch (api.getVarbit(VARBIT_BANK_SETTING)) {
            case 1 -> BankSetting.PRESETS;
            default -> BankSetting.TRANSFER;
        };
    }

    // ========================== Helpers ==========================

    /**
     * Find a component in the bank grid that holds the given item.
     */
    private Component findBankItem(int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .itemId(itemId)
                .build());
        // Filter to the bank grid component specifically
        return comps.stream()
                .filter(c -> c.componentId() == BANK_COMPONENT || c.subComponentId() >= 0)
                .findFirst().orElse(comps.isEmpty() ? null : comps.getFirst());
    }

    /**
     * Find a component in the backpack section of the bank interface.
     */
    private Component findBackpackItem(int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .itemId(itemId)
                .build());
        return comps.stream()
                .filter(c -> c.componentId() == BACKPACK_COMPONENT || c.subComponentId() >= 0)
                .findFirst().orElse(comps.isEmpty() ? null : comps.getFirst());
    }

    /**
     * Find a component by its right-click option text within the bank interface.
     */
    private boolean interactByOption(String option) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .optionPattern(option)
                .optionMatchType("contains")
                .build());
        if (comps.isEmpty()) return false;
        Component comp = comps.getFirst();
        return interactComponent(comp, option);
    }

    private boolean interactComponent(Component comp, String option) {
        List<String> options = api.getComponentOptions(comp.interfaceId(), comp.componentId());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).contains(option)) {
                return queueComponentAction(api, comp, i + 1);
            }
        }
        // Fallback: interact with default option
        return queueComponentAction(api, comp, 1);
    }

    private boolean queueRawAction(int optionIndex, int subComponent, int hash) {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, optionIndex, subComponent, hash));
        return true;
    }

    private int mapDepositOption(TransferAmount amount) {
        return switch (amount) {
            case ONE -> 2;
            case FIVE -> 3;
            case TEN -> 4;
            case CUSTOM -> 5;
            case ALL -> 7;
            default -> 1;
        };
    }

    private int mapWithdrawOption(TransferAmount amount) {
        return switch (amount) {
            case ONE -> 1;
            case FIVE -> 3;
            case TEN -> 4;
            case CUSTOM -> 5;
            case ALL -> 7;
            default -> -1;
        };
    }

    // ========================== Enums ==========================

    /**
     * Transfer quantity presets for bank deposit and withdraw operations.
     */
    public enum TransferAmount {
        ONE, FIVE, TEN, ALL, CUSTOM, OTHER
    }

    /**
     * Bank withdraw mode: items are withdrawn as physical items or bank notes.
     */
    public enum WithdrawMode {
        ITEM, NOTE
    }

    /**
     * The side panel view shown alongside the bank grid.
     */
    public enum SideView {
        BACKPACK, EQUIPMENT, FAMILIAR
    }

    /**
     * The bank interface mode: standard transfer controls or preset selection.
     */
    public enum BankSetting {
        TRANSFER, PRESETS
    }
}
