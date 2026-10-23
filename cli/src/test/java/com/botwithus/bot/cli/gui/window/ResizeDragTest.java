package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResizeDragTest {

    private static final WindowRect START = new WindowRect(100, 100, 800, 600);
    private static final int MIN_W = 400;
    private static final int MIN_H = 300;

    private static WindowRect drag(ResizeEdge edge, int dx, int dy) {
        ScreenPoint grab = new ScreenPoint(500, 500);
        ResizeDrag drag = new ResizeDrag(START, edge, grab);
        return drag.rectAt(new ScreenPoint(grab.x() + dx, grab.y() + dy), MIN_W, MIN_H);
    }

    @Test
    void east_growsTheWidthOnly() {
        assertEquals(new WindowRect(100, 100, 850, 600), drag(ResizeEdge.EAST, 50, 30));
    }

    @Test
    void south_growsTheHeightOnly() {
        assertEquals(new WindowRect(100, 100, 800, 640), drag(ResizeEdge.SOUTH, 70, 40));
    }

    @Test
    void west_movesTheLeftEdgeAndKeepsTheRightOnePut() {
        assertEquals(new WindowRect(60, 100, 840, 600), drag(ResizeEdge.WEST, -40, 0));
    }

    @Test
    void north_movesTheTopEdgeAndKeepsTheBottomOnePut() {
        assertEquals(new WindowRect(100, 150, 800, 550), drag(ResizeEdge.NORTH, 0, 50));
    }

    @Test
    void northWest_movesBothNearEdges() {
        assertEquals(new WindowRect(80, 90, 820, 610), drag(ResizeEdge.NORTH_WEST, -20, -10));
    }

    @Test
    void southEast_growsBothFarEdges() {
        assertEquals(new WindowRect(100, 100, 830, 620), drag(ResizeEdge.SOUTH_EAST, 30, 20));
    }

    @Test
    void east_pastTheMinimum_stopsAtIt() {
        assertEquals(new WindowRect(100, 100, MIN_W, 600), drag(ResizeEdge.EAST, -700, 0));
    }

    @Test
    void west_pastTheMinimum_pinsTheRightEdge() {
        // The right edge is at 900; a 400-wide window then starts at 500 however far the drag goes.
        assertEquals(new WindowRect(500, 100, MIN_W, 600), drag(ResizeEdge.WEST, 900, 0));
    }

    @Test
    void north_pastTheMinimum_pinsTheBottomEdge() {
        assertEquals(new WindowRect(100, 400, 800, MIN_H), drag(ResizeEdge.NORTH, 0, 1000));
    }

    @Test
    void noMovement_isTheStartRect() {
        assertEquals(START, drag(ResizeEdge.SOUTH_WEST, 0, 0));
    }
}
