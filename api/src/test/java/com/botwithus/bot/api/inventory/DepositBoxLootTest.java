package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Deposit box and loot window. The deposit button lists its option on op 3 and the slot's
 * Deposit-All on op 6, so a facade that sent a fixed op, or the wrong component, queues a
 * different action from the one asserted.
 */
class DepositBoxLootTest {

    private static final int BOX = 11;
    private static final int BOX_GRID = 19;
    private static final int CARRIED_BUTTON = 5;
    private static final int CLOSE_BUTTON = 23;
    private static final int LOOT_WINDOW = 1622;
    private static final int LOOT_INV = 773;
    private static final int LOOT_ALL_BUTTON = 15;
    private static final int LOGS = 1511;
    private static final int ORE = 440;
    private static final int BONES = 526;

    private final InventoryFixture fx = new InventoryFixture();
    private final DepositBox box = new DepositBox(fx.api);
    private final LootInventory loot = new LootInventory(fx.api);

    @BeforeEach
    void setUp() {
        when(fx.api.components()).thenReturn(new Components(fx.api));
        when(fx.api.getComponentOps(anyInt(), anyInt(), anyInt())).thenReturn(ComponentOps.NONE);
        when(fx.api.getInterfaceTree(BOX, 0)).thenReturn(List.of(
                node(BOX, 0, -1, -1), node(BOX, CARRIED_BUTTON, -1, -1), node(BOX, CLOSE_BUTTON, -1, -1)));
        ops(BOX, CARRIED_BUTTON, -1, 3, DepositBox.DEPOSIT_CARRIED);
        ops(BOX, CLOSE_BUTTON, -1, 1, DepositBox.CLOSE);
        fx.item(LOGS, "Logs", 0).item(ORE, "Iron ore", 0).backpack(LOGS, ORE, ORE, LOGS);
    }

    @Test
    void depositBox_closed_doesNothing() {
        assertFalse(box.depositAll());
        assertFalse(box.depositAllExcept(LOGS));
        assertEquals(List.of(), fx.queued());
    }

    @Test
    void depositAll_pressesTheButtonListingTheOption_withItsOp() {
        when(fx.api.isInterfaceOpen(BOX)).thenReturn(true);

        assertTrue(box.depositAll());
        assertFalse(box.depositWorn());

        assertEquals(List.of(new GameAction(ActionTypes.COMPONENT, 3, -1,
                Interfaces.componentHash(BOX, CARRIED_BUTTON))), fx.queued());
    }

    @Test
    void depositAllExcept_depositsEachOtherItemOnce_fromItsFirstSlot() {
        when(fx.api.isInterfaceOpen(BOX)).thenReturn(true);
        ops(BOX, BOX_GRID, 1, 6, DepositBox.DEPOSIT_ALL);

        assertTrue(box.depositAllExcept("logs"));

        assertEquals(List.of(new GameAction(ActionTypes.COMPONENT, 6, 1,
                Interfaces.componentHash(BOX, BOX_GRID))), fx.queued());
    }

    @Test
    void loot_contentsComeFromInventory773_whetherOrNotTheWindowIsOpen() {
        fx.inventory(LOOT_INV, 2, BONES, InventoryFixture.EMPTY_ITEM).item(BONES, "Bones", 0);

        assertEquals(List.of(BONES), loot.contents().stream().map(i -> i.itemId()).toList());
        assertTrue(loot.items().contains("Bones"));
        assertFalse(loot.isOpen());
        assertFalse(loot.lootAll());
    }

    @Test
    void lootAllAndTake_clickTheWindowsOwnOptions() {
        when(fx.api.isInterfaceOpen(LOOT_WINDOW)).thenReturn(true);
        when(fx.api.getInterfaceTree(LOOT_WINDOW, 0)).thenReturn(List.of(
                node(LOOT_WINDOW, 0, -1, -1), node(LOOT_WINDOW, LOOT_ALL_BUTTON, -1, -1),
                node(LOOT_WINDOW, 11, 0, BONES)));
        ops(LOOT_WINDOW, LOOT_ALL_BUTTON, -1, 1, LootInventory.LOOT_ALL);
        ops(LOOT_WINDOW, 11, 0, 2, LootInventory.TAKE);

        assertTrue(loot.lootAll());
        assertTrue(loot.take(BONES));
        assertFalse(loot.take(LOGS));

        assertEquals(List.of(
                new GameAction(ActionTypes.COMPONENT, 1, -1, Interfaces.componentHash(LOOT_WINDOW, LOOT_ALL_BUTTON)),
                new GameAction(ActionTypes.COMPONENT, 2, 0, Interfaces.componentHash(LOOT_WINDOW, 11))),
                fx.queued());
    }

    private void ops(int iface, int comp, int sub, int op, String name) {
        when(fx.api.getComponentOps(iface, comp, sub)).thenReturn(
                new ComponentOps(1 << op, List.of(new ComponentOp(op, name, true))));
    }

    private static ComponentTreeNode node(int iface, int comp, int sub, int itemId) {
        Component data = new Component(iface, comp, sub, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                0, "", 0, -1, itemId, itemId == -1 ? -1 : 1, List.of());
        return new ComponentTreeNode(data, comp == 0 ? -1 : 0);
    }
}
