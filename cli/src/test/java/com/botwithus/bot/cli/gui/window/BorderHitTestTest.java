package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BorderHitTestTest {

    private static final float W = 1000f;
    private static final float H = 600f;
    private static final BorderHitTest HIT = new BorderHitTest(6f, 16f);

    @ParameterizedTest(name = "({0}, {1}) -> {2}")
    @CsvSource({
            // Straight edges, inside the 6 px band.
            "500, 0, NORTH",
            "500, 5.9, NORTH",
            "500, 599, SOUTH",
            "500, 594, SOUTH",
            "0, 300, WEST",
            "999, 300, EAST",
            // Corners take a 16 px run along either edge they join.
            "0, 0, NORTH_WEST",
            "15, 2, NORTH_WEST",
            "2, 15, NORTH_WEST",
            "990, 1, NORTH_EAST",
            "998, 12, NORTH_EAST",
            "3, 590, SOUTH_WEST",
            "999, 599, SOUTH_EAST",
            "985, 597, SOUTH_EAST",
    })
    void edgeAt_insideTheBand_namesTheEdge(float x, float y, ResizeEdge expected) {
        assertEquals(Optional.of(expected), HIT.edgeAt(x, y, W, H));
    }

    @ParameterizedTest(name = "({0}, {1}) -> none")
    @CsvSource({
            "500, 6",       // first row past the top band
            "500, 300",     // the middle
            "6, 300",       // first column past the left band
            "993, 300",     // last column before the right band
            "16, 8",        // past the corner run and below the top band
            "-1, 300",      // outside the window
            "500, 600",     // one past the bottom row
    })
    void edgeAt_outsideTheBand_isNone(float x, float y) {
        assertEquals(Optional.empty(), HIT.edgeAt(x, y, W, H));
    }

    @Test
    void edgeAt_cornerRunLongerThanHalfTheWindow_stillPicksTheNearerCorner() {
        // 700 px runs from both ends overlap across the middle 400 px of a 1000 px edge.
        BorderHitTest wide = new BorderHitTest(6f, 700f);

        assertEquals(Optional.of(ResizeEdge.NORTH_EAST), wide.edgeAt(600f, 0f, W, H));
        assertEquals(Optional.of(ResizeEdge.NORTH_WEST), wide.edgeAt(399f, 0f, W, H));
    }
}
