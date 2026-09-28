package com.botwithus.bot.core.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocFootprintTest {

    @Test
    void of_unturned_keepsCacheDimensions() {
        LocFootprint fp = LocFootprint.of(10, 20, 2, 3, 0);

        assertEquals(2, fp.width());
        assertEquals(3, fp.depth());
    }

    @Test
    void of_quarterAndThreeQuarterTurns_swapDimensions() {
        assertEquals(new LocFootprint(10, 20, 3, 2), LocFootprint.of(10, 20, 2, 3, 1));
        assertEquals(new LocFootprint(10, 20, 3, 2), LocFootprint.of(10, 20, 2, 3, 3));
        assertEquals(new LocFootprint(10, 20, 2, 3), LocFootprint.of(10, 20, 2, 3, 2));
    }

    @Test
    void of_nonPositiveSize_readsAsOneTile() {
        assertEquals(new LocFootprint(10, 20, 1, 1), LocFootprint.of(10, 20, 0, -1, 0));
    }

    @Test
    void distanceTo_insideFootprint_isZero() {
        LocFootprint fp = new LocFootprint(100, 200, 4, 4);

        assertEquals(0, fp.distanceTo(100, 200));
        assertEquals(0, fp.distanceTo(103, 203));
    }

    @Test
    void distanceTo_outsideFootprint_isChebyshevToNearestEdge() {
        LocFootprint fp = new LocFootprint(100, 200, 4, 4);

        assertEquals(1, fp.distanceTo(104, 203));
        assertEquals(1, fp.distanceTo(99, 199));
        assertEquals(2, fp.distanceTo(101, 198));
        assertEquals(3, fp.distanceTo(106, 197));
    }

    @Test
    void constructor_emptyFootprint_throws() {
        assertThrows(IllegalArgumentException.class, () -> new LocFootprint(0, 0, 0, 1));
    }
}
