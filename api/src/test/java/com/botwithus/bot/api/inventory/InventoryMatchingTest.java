package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** Exact-name, Pattern, category and option operations on {@link InventoryContainer}. */
class InventoryMatchingTest {

    private static final int LOGS = 1511;
    private static final int OAK_LOGS = 1521;
    private static final int BONES = 526;
    private static final int TINDERBOX = 590;
    private static final int LOG_CATEGORY = 22;
    private static final int TOOL_CATEGORY = 3;
    private static final int OP_BURY = 1;

    private final InventoryFixture fx = new InventoryFixture()
            .item(LOGS, "Logs", LOG_CATEGORY, "Craft")
            .item(OAK_LOGS, "Oak logs", LOG_CATEGORY, "Craft")
            .item(BONES, "Bones", 0, "Bury")
            .item(TINDERBOX, "Tinderbox", TOOL_CATEGORY, "Use")
            .backpack(OAK_LOGS, InventoryFixture.EMPTY_ITEM, LOGS, BONES, BONES);
    private final Backpack pack = fx.backpack;

    @Test
    void exactNames_matchTheWholeNameIgnoringCase() {
        assertEquals(2, pack.getFirstExact("logs").slot(), "not Oak logs in slot 0");
        assertNull(pack.getFirstExact("Log"));
        assertTrue(pack.containsAllOf("LOGS", "bones"));
        assertFalse(pack.containsAllOf("Logs", "Tinderbox"));
        assertTrue(pack.containsAnyOf("Tinderbox", "Oak logs"));
        assertFalse(pack.containsAnyOf("Tinderbox", "Log"));
    }

    @Test
    void patterns_matchTheWholeName() {
        Pattern anyLogs = Pattern.compile("(?i).*logs");
        assertTrue(pack.contains(anyLogs));
        assertEquals(2, pack.count(anyLogs));
        assertEquals(List.of(0, 2), slots(pack.getAll(anyLogs)));
        assertFalse(pack.contains(Pattern.compile("Bone")), "matches(), not find()");
        assertEquals(2, pack.count(Pattern.compile("Bones")));
    }

    @Test
    void containsAnyExcept_ignoresEmptySlots() {
        assertTrue(pack.containsAnyExcept(LOGS, OAK_LOGS));
        assertFalse(pack.containsAnyExcept(LOGS, OAK_LOGS, BONES));
        assertTrue(pack.containsAnyExcept("Logs", "Oak logs"));
        assertFalse(pack.containsAnyExcept("logs", "OAK LOGS", "bones"));
    }

    @Test
    void categoriesAndCacheOptions() {
        assertTrue(pack.containsCategory(LOG_CATEGORY));
        assertFalse(pack.containsCategory(TOOL_CATEGORY));
        assertEquals(List.of(0, 2), slots(pack.getAllInCategory(LOG_CATEGORY)));
        assertEquals(List.of(3, 4), slots(pack.getItemsWithOption("bury")));
    }

    @Test
    void interactByExactName_clicksThatItemsLiveOp() {
        when(fx.api.getComponentOps(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID, 3))
                .thenReturn(new ComponentOps(1, List.of(new ComponentOp(OP_BURY, "Bury", true))));

        assertTrue(pack.interact("bones", "Bury"));

        GameAction expected = new GameAction(ActionTypes.COMPONENT, OP_BURY, 3,
                Interfaces.componentHash(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID));
        assertEquals(List.of(expected), fx.queued());
    }

    @Test
    void interactByName_absentItem_sendsNothing() {
        assertFalse(pack.interact("Log", "Craft"));
        assertFalse(pack.interactFirst(Pattern.compile("Nothing"), "Craft"));
        assertEquals(List.of(), fx.queued());
    }

    private static List<Integer> slots(List<InventoryItem> items) {
        return items.stream().map(InventoryItem::slot).toList();
    }
}
