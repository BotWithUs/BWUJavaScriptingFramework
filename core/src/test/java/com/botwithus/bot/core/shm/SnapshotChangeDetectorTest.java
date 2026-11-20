package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.InventoryChangeEvent;
import com.botwithus.bot.api.event.SkillChangeEvent;
import com.botwithus.bot.api.snapshot.InventoryItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotChangeDetector} over hand-built tick states. Item ids, quantities and xp are
 * all distinct non-default values, so a diff that swapped old and new, or reported the wrong
 * slot or skill, fails on the value rather than passing by coincidence.
 */
class SnapshotChangeDetectorTest {

    private static final int BACKPACK = 93;
    private static final int BANK = 95;
    private static final int LOGS = 1511;
    private static final int COINS = 995;
    private static final int EMPTY = -1;
    private static final int WOODCUTTING = 8;

    private final List<GameEvent> events = new ArrayList<>();
    private final SnapshotChangeDetector detector = new SnapshotChangeDetector(events::add);

    @Test
    void firstInGameTick_isTheBaselineAndPublishesNothing() {
        detector.observe(tick(100, pack(item(0, LOGS, 1)), skills(13_000, 30)));

        assertTrue(events.isEmpty());
    }

    @Test
    void slotChanges_publishOneEventPerChangedSlot_withOldAndNew() {
        detector.observe(tick(100, pack(item(0, LOGS, 1), item(1, COINS, 50), empty(2)), skills(13_000, 30)));
        detector.observe(tick(101, pack(empty(0), item(1, COINS, 75), item(2, LOGS, 1)), skills(13_000, 30)));

        assertEquals(3, events.size());
        InventoryChangeEvent dropped = assertInstanceOf(InventoryChangeEvent.class, events.get(0));
        assertEquals(BACKPACK, dropped.invId());
        assertEquals(0, dropped.slot());
        assertEquals(LOGS, dropped.oldItem().itemId());
        assertTrue(dropped.isRemoved());
        InventoryChangeEvent stack = assertInstanceOf(InventoryChangeEvent.class, events.get(1));
        assertEquals(25, stack.quantityDelta());
        assertEquals(101, stack.serverTick());
        InventoryChangeEvent added = assertInstanceOf(InventoryChangeEvent.class, events.get(2));
        assertTrue(added.isAdded());
        assertEquals(2, added.slot());
    }

    @Test
    void sameTickObservedTwice_isNotDiffedAgain() {
        detector.observe(tick(100, pack(item(0, LOGS, 1)), skills(13_000, 30)));
        detector.observe(tick(100, pack(empty(0)), skills(13_000, 30)));

        assertTrue(events.isEmpty());
    }

    @Test
    void inventoryAppearingForTheFirstTime_isABaselineNotAFlood() {
        detector.observe(tick(100, Map.of(BACKPACK, List.of(item(0, LOGS, 1))), skills(13_000, 30)));
        detector.observe(tick(101, Map.of(BACKPACK, List.of(item(0, LOGS, 1)),
                BANK, List.of(item(0, COINS, 1_000_000), item(1, LOGS, 400))), skills(13_000, 30)));

        assertTrue(events.isEmpty());
    }

    @Test
    void skillXpAndLevel_publishOneEvent() {
        detector.observe(tick(100, pack(), skills(13_000, 30)));
        detector.observe(tick(101, pack(), skills(13_363, 31)));

        SkillChangeEvent change = assertInstanceOf(SkillChangeEvent.class, events.getFirst());
        assertEquals(WOODCUTTING, change.skillId());
        assertEquals(363, change.experienceGained());
        assertEquals(1, change.levelsGained());
        assertTrue(change.isLevelUp());
    }

    @Test
    void leavingTheGame_resetsTheBaseline() {
        detector.observe(tick(100, pack(item(0, LOGS, 1)), skills(13_000, 30)));
        detector.observe(new TickState(101, false, Map.of(), Map.of()));
        detector.observe(tick(102, pack(empty(0)), skills(20_000, 35)));

        assertTrue(events.isEmpty());
    }

    private static TickState tick(int serverTick, Map<Integer, List<InventoryItem>> invs,
                                  Map<Integer, SkillEntry> skills) {
        return new TickState(serverTick, true, invs, skills);
    }

    private static Map<Integer, List<InventoryItem>> pack(InventoryItem... items) {
        return Map.of(BACKPACK, List.of(items));
    }

    private static Map<Integer, SkillEntry> skills(int xp, int level) {
        return Map.of(WOODCUTTING, new SkillEntry(WOODCUTTING, xp, level, level));
    }

    private static InventoryItem item(int slot, int id, int quantity) {
        return new InventoryItem(slot, id, quantity);
    }

    private static InventoryItem empty(int slot) {
        return new InventoryItem(slot, EMPTY, 0);
    }
}
