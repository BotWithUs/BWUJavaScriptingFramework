package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowRectTest {

    private static final WindowRect AREA = new WindowRect(0, 0, 1920, 1040);

    @Test
    void overlapArea_disjoint_isZero() {
        assertEquals(0L, new WindowRect(2000, 0, 100, 100).overlapArea(AREA));
    }

    @Test
    void overlapArea_partial_countsSharedPixelsOnly() {
        assertEquals(50L * 40L, new WindowRect(1870, 1000, 100, 100).overlapArea(AREA));
    }

    @Test
    void centredIn_centresTheSameSize() {
        assertEquals(new WindowRect(410, 170, 1100, 700), new WindowRect(5, 5, 1100, 700).centredIn(AREA));
    }

    @Test
    void fittedInto_hangingOffTheRight_movesInsideKeepingItsSize() {
        assertEquals(new WindowRect(820, 300, 1100, 700), new WindowRect(1500, 300, 1100, 700).fittedInto(AREA));
    }

    @Test
    void fittedInto_largerThanTheArea_shrinksToIt() {
        assertEquals(AREA, new WindowRect(-50, -50, 3000, 2000).fittedInto(AREA));
    }

    @Test
    void fittedInto_areaOffTheOrigin_staysInThatArea() {
        WindowRect left = new WindowRect(-1280, 200, 1280, 984);

        assertEquals(new WindowRect(-1280, 484, 1100, 700), new WindowRect(-1500, 900, 1100, 700).fittedInto(left));
    }
}
