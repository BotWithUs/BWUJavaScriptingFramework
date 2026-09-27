package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementRestoreTest {

    private static final PlacementRestore RESTORE = new PlacementRestore(1100, 700, 640, 400, 100);

    /** 1920x1080 with a 40 px taskbar along the bottom. */
    private static final WindowRect PRIMARY = new WindowRect(0, 0, 1920, 1040);
    /** A 1280x1024 monitor to the left of the primary one. */
    private static final WindowRect LEFT = new WindowRect(-1280, 16, 1280, 984);

    private static WindowPlacement restore(WindowRect saved, boolean isMaximised, WindowRect... monitors) {
        return RESTORE.resolve(Optional.of(new WindowPlacement(saved, isMaximised)), List.of(monitors));
    }

    @Test
    void nothingSaved_opensMaximisedOnThePrimaryWorkArea_restoringToTheDefaultSizeCentred() {
        WindowPlacement placement = RESTORE.resolve(Optional.empty(), List.of(PRIMARY, LEFT));

        assertEquals(new WindowPlacement(new WindowRect(410, 170, 1100, 700), true), placement);
    }

    @Test
    void nothingSaved_primarySmallerThanTheDefault_restoresToThePrimaryWorkArea() {
        WindowRect small = new WindowRect(0, 0, 1024, 728);

        WindowPlacement placement = RESTORE.resolve(Optional.empty(), List.of(small));

        assertEquals(new WindowPlacement(new WindowRect(0, 14, 1024, 700), true), placement);
    }

    @Test
    void savedOnScreen_isKeptExactly() {
        WindowRect saved = new WindowRect(200, 120, 1200, 760);

        assertEquals(new WindowPlacement(saved, false), restore(saved, false, PRIMARY, LEFT));
        assertEquals(new WindowPlacement(saved, true), restore(saved, true, PRIMARY, LEFT));
    }

    @Test
    void savedOnASecondMonitorThatIsStillThere_staysThere() {
        WindowRect saved = new WindowRect(-1200, 100, 900, 700);

        assertEquals(new WindowPlacement(saved, false), restore(saved, false, PRIMARY, LEFT));
    }

    @Test
    void savedOnAMonitorThatIsGone_comesBackCentredOnThePrimaryWithItsSize() {
        WindowRect saved = new WindowRect(-1200, 100, 900, 700);

        assertEquals(new WindowPlacement(new WindowRect(510, 170, 900, 700), true),
                restore(saved, true, PRIMARY));
    }

    @Test
    void savedBarelyOnScreen_countsAsOffScreen() {
        // Only a 50 x 50 corner is on the primary monitor, too little to grab.
        WindowRect saved = new WindowRect(1870, 990, 900, 700);

        assertEquals(new WindowPlacement(new WindowRect(510, 170, 900, 700), false),
                restore(saved, false, PRIMARY));
    }

    @Test
    void savedHangingOffTheEdge_isMovedInside() {
        WindowRect saved = new WindowRect(1500, -40, 1100, 700);

        assertEquals(new WindowPlacement(new WindowRect(820, 0, 1100, 700), false),
                restore(saved, false, PRIMARY));
    }

    @Test
    void savedLargerThanItsMonitorNow_shrinksToTheWorkArea() {
        WindowRect saved = new WindowRect(0, 0, 2560, 1400);

        assertEquals(new WindowPlacement(PRIMARY, false), restore(saved, false, PRIMARY));
    }

    @Test
    void savedAcrossTwoMonitors_goesToTheOneHoldingMoreOfIt() {
        // 300 px on the left monitor, 800 px on the primary.
        WindowRect saved = new WindowRect(-300, 100, 1100, 700);

        assertEquals(new WindowPlacement(new WindowRect(0, 100, 1100, 700), false),
                restore(saved, false, LEFT, PRIMARY));
    }

    @Test
    void savedSmallerThanTheMinimum_growsToIt() {
        WindowRect saved = new WindowRect(100, 100, 200, 200);

        assertEquals(new WindowPlacement(new WindowRect(100, 100, 640, 400), false),
                restore(saved, false, PRIMARY));
    }

    @Test
    void noMonitorReported_keepsTheSavedPlacement() {
        WindowRect saved = new WindowRect(100, 100, 900, 700);

        assertEquals(new WindowPlacement(saved, true), restore(saved, true));
    }

    @Test
    void noMonitorAndNothingSaved_isTheDefaultSizeAtTheOrigin() {
        assertEquals(new WindowPlacement(new WindowRect(0, 0, 1100, 700), true),
                RESTORE.resolve(Optional.empty(), List.of()));
    }
}
