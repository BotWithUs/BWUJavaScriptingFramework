package com.botwithus.bot.api.world;

import com.botwithus.bot.api.domain.NavigationAPI;
import com.botwithus.bot.api.entities.EntityContext;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Containment and sampling for the three stock {@link Area} shapes. */
class AreaTest {

    private static final long SEED = 7;
    private static final int DRAWS = 2_000;

    private final SplittableRandom random = new SplittableRandom(SEED);

    @Test
    void rectangle_containsBothCornersAndNothingPast() {
        RectangularArea rect = RectangularArea.of(new Coordinate(3205, 3210, 0), new Coordinate(3200, 3200, 0));
        assertTrue(rect.contains(new Coordinate(3200, 3200, 0)));
        assertTrue(rect.contains(new Coordinate(3205, 3210, 0)));
        assertFalse(rect.contains(new Coordinate(3206, 3205, 0)));
        assertFalse(rect.contains(new Coordinate(3199, 3205, 0)));
        assertFalse(rect.contains(new Coordinate(3202, 3205, 1)), "other plane");
        assertEquals(6, rect.width());
        assertEquals(11, rect.height());
    }

    @Test
    void rectangle_cornersOnDifferentPlanes_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> RectangularArea.of(new Coordinate(0, 0, 0), new Coordinate(1, 1, 1)));
    }

    @Test
    void circle_containsByEuclideanDistance() {
        CircularArea circle = new CircularArea(new Coordinate(100, 100, 0), 3);
        assertTrue(circle.contains(new Coordinate(103, 100, 0)));
        assertTrue(circle.contains(new Coordinate(102, 102, 0)), "sqrt 8 <= 3");
        assertFalse(circle.contains(new Coordinate(103, 103, 0)), "corner of the square is outside");
        assertFalse(circle.contains(new Coordinate(100, 100, 1)));
    }

    @Test
    void polygon_triangle_containsInteriorAndOutlineOnly() {
        PolygonalArea triangle = PolygonalArea.of(
                new Coordinate(0, 0, 0), new Coordinate(10, 0, 0), new Coordinate(0, 10, 0));
        assertTrue(triangle.contains(new Coordinate(2, 2, 0)), "interior");
        assertTrue(triangle.contains(new Coordinate(5, 5, 0)), "on the hypotenuse");
        assertTrue(triangle.contains(new Coordinate(10, 0, 0)), "vertex");
        assertFalse(triangle.contains(new Coordinate(6, 6, 0)), "past the hypotenuse");
        assertFalse(triangle.contains(new Coordinate(2, 2, 1)), "other plane");
    }

    @Test
    void polygon_concave_excludesTheNotch() {
        PolygonalArea ell = PolygonalArea.of(
                new Coordinate(0, 0, 0), new Coordinate(10, 0, 0), new Coordinate(10, 4, 0),
                new Coordinate(4, 4, 0), new Coordinate(4, 10, 0), new Coordinate(0, 10, 0));
        assertTrue(ell.contains(new Coordinate(2, 8, 0)));
        assertTrue(ell.contains(new Coordinate(8, 2, 0)));
        assertFalse(ell.contains(new Coordinate(8, 8, 0)), "inside the bounding box, outside the L");
    }

    @Test
    void polygon_degenerateLine_stillContainsItsOutline() {
        PolygonalArea line = PolygonalArea.of(
                new Coordinate(0, 0, 0), new Coordinate(5, 0, 0), new Coordinate(2, 0, 0));
        assertTrue(line.contains(new Coordinate(3, 0, 0)));
        Coordinate tile = line.randomTile(random);
        assertTrue(line.contains(tile));
        assertEquals(6, line.tiles().size());
    }

    @Test
    void polygon_validation() {
        assertThrows(IllegalArgumentException.class,
                () -> PolygonalArea.of(new Coordinate(0, 0, 0), new Coordinate(1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> PolygonalArea.of(
                new Coordinate(0, 0, 0), new Coordinate(1, 0, 0), new Coordinate(0, 1, 1)));
    }

    @Test
    void randomTile_alwaysLandsInside_andCoversTheArea() {
        List<Area> areas = List.of(
                RectangularArea.of(new Coordinate(0, 0, 2), new Coordinate(2, 2, 2)),
                new CircularArea(new Coordinate(50, 50, 0), 1),
                PolygonalArea.of(new Coordinate(0, 0, 0), new Coordinate(4, 0, 0), new Coordinate(0, 4, 0)));
        for (Area area : areas) {
            Set<Coordinate> distinct = new HashSet<>();
            for (int i = 0; i < DRAWS; i++) {
                Coordinate tile = area.randomTile(random);
                assertTrue(area.contains(tile), area + " drew " + tile);
                distinct.add(tile);
            }
            assertEquals(tileCount(area), distinct.size(), area + " never drew some tiles");
        }
    }

    @Test
    void randomReachableTile_returnsTheFirstReachableDraw() {
        NavigationAPI nav = mock(NavigationAPI.class);
        when(nav.isReachable(anyInt(), anyInt())).thenReturn(false, false, true);
        Area area = RectangularArea.around(new Coordinate(3200, 3200, 0), 2);

        Optional<Coordinate> tile = area.randomReachableTile(nav, 5, random);

        assertTrue(tile.isPresent());
        assertTrue(area.contains(tile.get()));
        verify(nav, times(3)).isReachable(anyInt(), anyInt());
    }

    @Test
    void randomReachableTile_noneReachable_isEmptyAfterTheAttempts() {
        NavigationAPI nav = mock(NavigationAPI.class);
        Area area = RectangularArea.around(new Coordinate(3200, 3200, 0), 2);

        assertEquals(Optional.empty(), area.randomReachableTile(nav, 4, random));
        verify(nav, times(4)).isReachable(anyInt(), anyInt());
    }

    @Test
    void contains_entity_usesItsTile() {
        Area area = RectangularArea.around(new Coordinate(10, 10, 0), 1);
        EntityContext inside = new Coordinate(11, 9, 0);
        EntityContext none = null;
        assertTrue(area.contains(inside));
        assertFalse(area.contains(none));
    }

    private static int tileCount(Area area) {
        int count = 0;
        for (int x = -1; x <= 60; x++) {
            for (int y = -1; y <= 60; y++) {
                if (area.contains(x, y, area.plane())) {
                    count++;
                }
            }
        }
        return count;
    }
}
