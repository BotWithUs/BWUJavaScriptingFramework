package com.botwithus.bot.cli.gui.pages.groups;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticks outlive the frame that drew them. The old Groups panel built its
 * selection afresh every frame, so whatever the user picked, "Add" acted on the
 * first entry.
 */
class TickedKeysTest {

    private static final String GROUP = "Woodcutters";
    private static final String OTHER_GROUP = "Questers";
    private static final List<String> ROWS = List.of("alpha", "bravo", "charlie");

    @Test
    void aTick_onTheSecondRow_isStillThereOnLaterFrames() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.toggle(GROUP, "bravo");

        List<String> firstFrame = ticks.ticked(GROUP, ROWS);
        List<String> secondFrame = ticks.ticked(GROUP, ROWS);

        assertAll(
                () -> assertEquals(List.of("bravo"), firstFrame),
                () -> assertEquals(List.of("bravo"), secondFrame),
                () -> assertTrue(ticks.isTicked(GROUP, "bravo")),
                () -> assertFalse(ticks.isTicked(GROUP, "alpha")));
    }

    @Test
    void ticked_listsInRowOrder_notTickOrder() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.toggle(GROUP, "charlie");
        ticks.toggle(GROUP, "alpha");

        assertEquals(List.of("alpha", "charlie"), ticks.ticked(GROUP, ROWS));
    }

    @Test
    void toggling_twice_unticks() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.toggle(GROUP, "alpha");
        ticks.toggle(GROUP, "alpha");

        assertEquals(List.of(), ticks.ticked(GROUP, ROWS));
    }

    @Test
    void aRowThatLeavesTheList_isForgotten_andDoesNotComeBackTicked() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.toggle(GROUP, "bravo");

        ticks.ticked(GROUP, List.of("alpha", "charlie"));

        assertEquals(List.of(), ticks.ticked(GROUP, ROWS));
    }

    @Test
    void anotherScope_startsWithNothingTicked_andForgetsTheOldTicks() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.toggle(GROUP, "bravo");

        assertAll(
                () -> assertEquals(List.of(), ticks.ticked(OTHER_GROUP, ROWS)),
                () -> assertEquals(List.of(), ticks.ticked(GROUP, ROWS)));
    }

    @Test
    void setAll_ticksAndUnticksEveryRow() {
        TickedKeys<String> ticks = new TickedKeys<>();

        ticks.setAll(GROUP, ROWS, true);
        List<String> all = ticks.ticked(GROUP, ROWS);
        ticks.setAll(GROUP, ROWS, false);

        assertAll(
                () -> assertEquals(ROWS, all),
                () -> assertEquals(List.of(), ticks.ticked(GROUP, ROWS)));
    }

    @Test
    void clear_unticksEverything() {
        TickedKeys<String> ticks = new TickedKeys<>();
        ticks.setAll(GROUP, ROWS, true);

        ticks.clear();

        assertEquals(List.of(), ticks.ticked(GROUP, ROWS));
    }
}
