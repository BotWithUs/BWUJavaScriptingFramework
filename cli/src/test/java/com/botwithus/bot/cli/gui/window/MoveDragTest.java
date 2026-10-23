package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveDragTest {

    @Test
    void grab_keepsThePointUnderTheCursor() {
        WindowRect window = new WindowRect(100, 50, 800, 600);
        MoveDrag drag = MoveDrag.grab(window, new ScreenPoint(300, 70));

        assertEquals(new WindowRect(-100, 400, 800, 600), drag.positionAt(window, new ScreenPoint(100, 420)));
    }

    @Test
    void grabRestoring_keepsTheCursorAtTheSameFractionAcrossTheBar() {
        WindowRect maximised = new WindowRect(0, 0, 2000, 1000);
        WindowRect normal = new WindowRect(300, 200, 800, 600);
        // Grabbed three quarters of the way across the maximised bar, 12 px down.
        MoveDrag drag = MoveDrag.grabRestoring(maximised, normal, new ScreenPoint(1500, 12));

        // The restored bar keeps the cursor three quarters across: 600 px into an 800 px window.
        assertEquals(new WindowRect(900, 8, 800, 600), drag.positionAt(normal, new ScreenPoint(1500, 20)));
    }

    @Test
    void grabRestoring_onAMaximisedWindowOffTheOrigin_measuresFromItsOwnLeftEdge() {
        WindowRect maximised = new WindowRect(-1280, 0, 1280, 1000);
        WindowRect normal = new WindowRect(0, 0, 640, 480);
        // A quarter of the way across the left-hand monitor.
        MoveDrag drag = MoveDrag.grabRestoring(maximised, normal, new ScreenPoint(-960, 10));

        assertEquals(new WindowRect(-1120, 0, 640, 480), drag.positionAt(normal, new ScreenPoint(-960, 10)));
    }

    @Test
    void grabRestoring_grabbedBelowTheRestoredHeight_keepsTheCursorOnTheWindow() {
        WindowRect maximised = new WindowRect(0, 0, 2000, 1000);
        WindowRect normal = new WindowRect(0, 0, 800, 300);
        MoveDrag drag = MoveDrag.grabRestoring(maximised, normal, new ScreenPoint(1000, 500));

        WindowRect moved = drag.positionAt(normal, new ScreenPoint(1000, 500));

        assertEquals(new WindowRect(600, 201, 800, 300), moved);
    }
}
