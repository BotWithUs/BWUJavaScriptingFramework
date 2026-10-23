package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementTrackerTest {

    private static final WindowRect NORMAL = new WindowRect(200, 100, 1100, 700);
    private static final WindowRect WORK_AREA = new WindowRect(0, 0, 1920, 1040);
    /** Where Windows parks a minimised window. */
    private static final WindowRect ICONIFIED = new WindowRect(-32000, -32000, 160, 28);

    private static final boolean MAXIMISED = true;
    private static final boolean NOT_MAXIMISED = false;
    private static final boolean MINIMISED = true;
    private static final boolean SHOWN = false;

    @Test
    void unchanged_hasNothingToSave() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, false));

        assertEquals(Optional.empty(), tracker.observe(NORMAL, NOT_MAXIMISED, SHOWN));
    }

    @Test
    void moved_savesTheNewBoundsOnce() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, false));
        WindowRect moved = NORMAL.movedTo(300, 150);

        assertAll(
                () -> assertEquals(Optional.of(new WindowPlacement(moved, false)),
                        tracker.observe(moved, NOT_MAXIMISED, SHOWN)),
                () -> assertEquals(Optional.empty(), tracker.observe(moved, NOT_MAXIMISED, SHOWN)));
    }

    @Test
    void maximised_keepsTheBoundsItRestoresTo() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, false));

        assertEquals(Optional.of(new WindowPlacement(NORMAL, true)),
                tracker.observe(WORK_AREA, MAXIMISED, SHOWN));
    }

    @Test
    void restoredFromMaximised_savesNotMaximised() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, false));
        tracker.observe(WORK_AREA, MAXIMISED, SHOWN);

        assertEquals(Optional.of(new WindowPlacement(NORMAL, false)),
                tracker.observe(NORMAL, NOT_MAXIMISED, SHOWN));
    }

    @Test
    void startedMaximised_theMaximisedBoundsAreNeverSavedAsTheNormalOnes() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, true));

        assertAll(
                () -> assertEquals(Optional.empty(), tracker.observe(WORK_AREA, MAXIMISED, SHOWN)),
                () -> assertEquals(Optional.of(new WindowPlacement(NORMAL, false)),
                        tracker.observe(NORMAL, NOT_MAXIMISED, SHOWN)));
    }

    @Test
    void minimised_isNeverSaved_andComingBackUnchangedSavesNothing() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, false));

        assertAll(
                () -> assertEquals(Optional.empty(), tracker.observe(ICONIFIED, NOT_MAXIMISED, MINIMISED)),
                () -> assertEquals(Optional.empty(), tracker.observe(NORMAL, NOT_MAXIMISED, SHOWN)));
    }

    @Test
    void minimisedWhileMaximised_comesBackMaximisedWithNothingToSave() {
        PlacementTracker tracker = new PlacementTracker(new WindowPlacement(NORMAL, true));

        assertAll(
                () -> assertEquals(Optional.empty(), tracker.observe(ICONIFIED, MAXIMISED, MINIMISED)),
                () -> assertEquals(Optional.empty(), tracker.observe(WORK_AREA, MAXIMISED, SHOWN)));
    }
}
