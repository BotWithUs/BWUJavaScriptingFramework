package com.botwithus.bot.api.model;

import com.botwithus.bot.api.world.Area;
import com.botwithus.bot.api.world.CircularArea;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.PolygonalArea;
import com.botwithus.bot.api.world.RectangularArea;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorldPathConfig#avoid}: which rectangle each shape becomes, and what is refused. */
class WorldPathConfigAvoidTest {

    private static final RectangularArea BOX = new RectangularArea(3200, 3210, 3215, 3225, 0);

    @Test
    void rectangle_isSentExactly() {
        assertEquals(List.of(BOX), WorldPathConfig.DEFAULT.avoid(BOX).avoidAreas());
    }

    @Test
    void circleAndPolygon_areCoveredByTheirBoundingBox() {
        CircularArea circle = new CircularArea(new Coordinate(3000, 3100, 1), 3);
        PolygonalArea triangle = PolygonalArea.of(new Coordinate(10, 10, 2), new Coordinate(20, 10, 2),
                new Coordinate(15, 30, 2));

        List<RectangularArea> rects = WorldPathConfig.builder().avoid(circle, triangle).build().avoidAreas();

        assertEquals(List.of(new RectangularArea(2997, 3097, 3003, 3103, 1),
                new RectangularArea(10, 10, 20, 30, 2)), rects);
    }

    @Test
    void anAreaWithNoBoundingBox_isRefused() {
        Area custom = new Area() {
            @Override
            public int plane() {
                return 0;
            }

            @Override
            public boolean contains(int x, int y, int plane) {
                return x == y;
            }

            @Override
            public Coordinate randomTile(RandomGenerator random) {
                return new Coordinate(1, 1, 0);
            }
        };

        assertThrows(IllegalArgumentException.class, () -> WorldPathConfig.DEFAULT.avoid(custom));
    }

    @Test
    void avoidAccumulates_andIsCappedAtSixtyFour() {
        WorldPathConfig config = WorldPathConfig.DEFAULT;
        for (int i = 0; i < WorldPathConfig.MAX_AVOID_AREAS; i++) {
            config = config.avoid(new RectangularArea(i, i, i, i, 0));
        }
        assertEquals(WorldPathConfig.MAX_AVOID_AREAS, config.avoidAreas().size());

        WorldPathConfig full = config;
        assertThrows(IllegalArgumentException.class, () -> full.avoid(BOX));
    }

    @Test
    void theOlderConstructorsAndDefault_avoidNothing() {
        assertTrue(WorldPathConfig.DEFAULT.avoidAreas().isEmpty());
        assertTrue(WorldPathConfig.builder().build().avoidAreas().isEmpty());
    }

    @Test
    void avoid_keepsEveryOtherField() {
        WorldPathConfig base = WorldPathConfig.builder().allowTeleports(false).allowDive(false).build();

        WorldPathConfig avoided = base.avoid(BOX);

        assertEquals(base.allowTeleports(), avoided.allowTeleports());
        assertEquals(base.movement(), avoided.movement());
    }
}
