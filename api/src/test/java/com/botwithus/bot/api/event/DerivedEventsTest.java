package com.botwithus.bot.api.event;

import com.botwithus.bot.api.snapshot.InventoryItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The convenience accessors on the host-derived change events. */
class DerivedEventsTest {

    private static final int LOGS = 1511;
    private static final int COINS = 995;

    @Test
    void inventoryChange_deltaAcrossTheFourShapes() {
        assertEquals(30, change(item(COINS, 70), item(COINS, 100)).quantityDelta());
        assertEquals(-70, change(item(COINS, 70), empty()).quantityDelta());
        assertEquals(1, change(empty(), item(LOGS, 1)).quantityDelta());
        assertEquals(5, change(item(COINS, 70), item(LOGS, 5)).quantityDelta());
        assertTrue(change(empty(), item(LOGS, 1)).isAdded());
        assertTrue(change(item(LOGS, 1), empty()).isRemoved());
        assertFalse(change(item(COINS, 1), item(COINS, 2)).isAdded());
    }

    @Test
    void skillChange_gainsAndLevelUp() {
        SkillChangeEvent change = new SkillChangeEvent(8, 13_000, 13_363, 30, 31, 30, 32, 5);

        assertEquals(363, change.experienceGained());
        assertEquals(1, change.levelsGained());
        assertTrue(change.isLevelUp());
    }

    private static InventoryChangeEvent change(InventoryItem was, InventoryItem is) {
        return new InventoryChangeEvent(93, 0, was, is, 1);
    }

    private static InventoryItem item(int id, int qty) {
        return new InventoryItem(0, id, qty);
    }

    private static InventoryItem empty() {
        return new InventoryItem(0, -1, 0);
    }
}
