package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The name- and Pattern-driven {@link Bank} verbs, {@link Bank#open()},
 * {@link Bank#loadLastPreset()} and {@link Bank#setWithdrawMode}. Asserted on the exact
 * actions queued, with every ref at its literal fallback (no gameval index).
 */
class BankByNameTest {

    private static final int LOGS = 1511;
    private static final int OAK_LOGS = 1521;
    private static final int BONES = 526;
    private static final int COINS = 995;
    private static final int BOOTH = 10_355;
    private static final int BANKER = 2_759;
    private static final int BANKER_INDEX = 12;
    /** Literal fallbacks of the bank components these verbs click. */
    private static final int ITEMS_COMPONENT = 201;
    private static final int BACKPACK_COMPONENT = 15;
    private static final int NOTE_BUTTON_COMPONENT = 127;
    private static final int OP_CLICK = 1;
    private static final int OP_ALL = 7;
    private static final int OP_ONE = 2;
    private static final int BANKCERT_VARP = 160;

    private final InventoryFixture fx = new InventoryFixture()
            .item(LOGS, "Logs", 0)
            .item(OAK_LOGS, "Oak logs", 0)
            .item(BONES, "Bones", 0)
            .item(COINS, "Coins", 0)
            .backpack(LOGS, BONES, LOGS, OAK_LOGS, COINS)
            .bank(BONES, OAK_LOGS);
    private final Bank bank = fx.bank;

    @Test
    void depositAllByIds_oneDepositAllPerDistinctItem() {
        fx.bankOpen();
        assertTrue(bank.depositAll(LOGS, COINS));
        assertEquals(List.of(packSlot(0, OP_ALL), packSlot(4, OP_ALL)), fx.queued());
    }

    @Test
    void depositAllByNames_wholeNamesOnly() {
        fx.bankOpen();
        assertTrue(bank.depositAll("logs"));
        assertEquals(List.of(packSlot(0, OP_ALL)), fx.queued(), "Oak logs is not Logs");
    }

    @Test
    void depositAllExcept_namesAndPatterns() {
        fx.bankOpen();
        assertTrue(bank.depositAllExcept("Coins", "Bones"));
        assertEquals(List.of(packSlot(0, OP_ALL), packSlot(3, OP_ALL)), fx.queued());
    }

    @Test
    void depositAllExcept_patterns() {
        fx.bankOpen();
        assertTrue(bank.depositAllExcept(Pattern.compile(".*[Ll]ogs")));
        assertEquals(List.of(packSlot(1, OP_ALL), packSlot(4, OP_ALL)), fx.queued());
    }

    @Test
    void depositAllExcept_ids() {
        fx.bankOpen();
        assertTrue(bank.depositAllExcept(LOGS, OAK_LOGS, BONES));
        assertEquals(List.of(packSlot(4, OP_ALL)), fx.queued());
    }

    @Test
    void bankClosed_nothingIsQueued() {
        assertFalse(bank.depositAll(LOGS));
        assertFalse(bank.depositAllExcept(COINS));
        assertFalse(bank.withdraw("Bones", Bank.TransferAmount.ONE));
        assertFalse(bank.deposit("Logs", Bank.TransferAmount.ONE));
        assertFalse(bank.setWithdrawMode(Bank.WithdrawMode.NOTE));
        assertEquals(List.of(), fx.queued());
    }

    @Test
    void withdrawAndDepositByNameAndPattern() {
        fx.bankOpen();
        assertTrue(bank.withdraw("oak logs", Bank.TransferAmount.ONE));
        assertTrue(bank.withdraw(Pattern.compile("Bon.*"), Bank.TransferAmount.ALL));
        assertTrue(bank.deposit("Oak logs", Bank.TransferAmount.ONE));
        assertFalse(bank.withdraw("Logs", Bank.TransferAmount.ONE), "only Oak logs is banked");
        assertEquals(List.of(
                bankSlot(1, OP_ONE), bankSlot(0, OP_ALL), packSlot(3, OP_ONE)), fx.queued());
    }

    @Test
    void setWithdrawMode_clicksTheToggleOnlyWhenTheModeDiffers() {
        fx.bankOpen();
        assertTrue(bank.setWithdrawMode(Bank.WithdrawMode.ITEM));
        assertEquals(List.of(), fx.queued(), "already ITEM");

        assertTrue(bank.setWithdrawMode(Bank.WithdrawMode.NOTE));
        assertEquals(List.of(button(NOTE_BUTTON_COMPONENT)), fx.queued());

        when(fx.api.getVarp(BANKCERT_VARP)).thenReturn(1);
        assertTrue(bank.setWithdrawMode(Bank.WithdrawMode.NOTE));
        assertEquals(1, fx.queued().size(), "already NOTE");
    }

    @Test
    void open_clicksTheNearestBoothsBankOption() {
        fx.object(BOOTH, "Bank booth", "Bank", "Collect");
        assertTrue(bank.open());
        List<GameAction> queued = fx.queued();
        assertEquals(1, queued.size());
        assertEquals(ActionTypes.OBJECT_OPTIONS[1], queued.getFirst().actionId());
        assertEquals(BOOTH, queued.getFirst().param1());
    }

    @Test
    void open_noBooth_talksToABanker() {
        fx.npc(BANKER_INDEX, BANKER, "Banker", "Talk-to", "Bank");
        assertTrue(bank.open());
        List<GameAction> queued = fx.queued();
        assertEquals(1, queued.size());
        assertEquals(ActionTypes.NPC_OPTIONS[2], queued.getFirst().actionId());
        assertEquals(BANKER_INDEX, queued.getFirst().param1());
    }

    @Test
    void open_alreadyOpen_queuesNothing() {
        fx.bankOpen().object(BOOTH, "Bank booth", "Bank");
        assertTrue(bank.open());
        assertEquals(List.of(), fx.queued());
    }

    @Test
    void open_nothingInRange_isFalse() {
        assertFalse(bank.open());
    }

    @Test
    void loadLastPreset_clicksThatOption() {
        fx.object(BOOTH, "Bank booth", "Bank", "Collect", Bank.LOAD_LAST_PRESET_OPTION);
        assertTrue(bank.loadLastPreset());
        assertEquals(ActionTypes.OBJECT_OPTIONS[3], fx.queued().getFirst().actionId());
    }

    @Test
    void loadLastPreset_nothingOffersIt_isFalse() {
        fx.object(BOOTH, "Bank booth", "Bank");
        assertFalse(bank.loadLastPreset());
    }

    private static GameAction packSlot(int slot, int op) {
        return new GameAction(ActionTypes.COMPONENT, op, slot,
                Interfaces.componentHash(Bank.INTERFACE_ID, BACKPACK_COMPONENT));
    }

    private static GameAction bankSlot(int slot, int op) {
        return new GameAction(ActionTypes.COMPONENT, op, slot,
                Interfaces.componentHash(Bank.INTERFACE_ID, ITEMS_COMPONENT));
    }

    private static GameAction button(int component) {
        return new GameAction(ActionTypes.COMPONENT, OP_CLICK, -1,
                Interfaces.componentHash(Bank.INTERFACE_ID, component));
    }
}
