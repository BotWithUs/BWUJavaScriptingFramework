package com.botwithus.bot.api.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinateTest {

    private static final double EPSILON = 1e-9;

    @Test
    void distances_ignoreThePlane() {
        Coordinate a = new Coordinate(3200, 3200, 0);
        Coordinate b = new Coordinate(3203, 3204, 1);
        assertEquals(4, a.chebyshevDistance(b));
        assertEquals(5.0, a.euclideanDistance(b), EPSILON);
    }

    @Test
    void region_lumbridgeTile() {
        Coordinate lumbridge = new Coordinate(3222, 3218, 0);
        assertEquals(50, lumbridge.regionX());
        assertEquals(50, lumbridge.regionY());
        assertEquals(12850, lumbridge.regionId());
        assertEquals(22, lumbridge.regionLocalX());
        assertEquals(18, lumbridge.regionLocalY());
    }

    @Test
    void regionId_putsRegionXInTheHighByte() {
        Coordinate varrock = new Coordinate(3212, 3424, 0);
        assertEquals(50, varrock.regionX());
        assertEquals(53, varrock.regionY());
        assertEquals((50 << 8) | 53, varrock.regionId());
    }

    @Test
    void offsets_deriveNewTiles() {
        Coordinate a = new Coordinate(10, 20, 1);
        assertEquals(new Coordinate(13, 18, 1), a.offset(3, -2));
        assertEquals(new Coordinate(10, 21, 2), a.offset(0, 1, 1));
        assertEquals(new Coordinate(10, 20, 3), a.withPlane(3));
    }

    @Test
    void plane_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(0, 0, -1));
    }
}
