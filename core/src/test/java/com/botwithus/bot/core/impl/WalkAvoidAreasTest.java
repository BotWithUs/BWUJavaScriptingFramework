package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.RouteLink;
import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.world.CircularArea;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.RectangularArea;
import com.botwithus.bot.core.worldwalker.StepKind;
import com.botwithus.bot.core.worldwalker.WwArea;
import com.botwithus.bot.core.worldwalker.WwPathResult;
import com.botwithus.bot.core.worldwalker.WwStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a walk's config becomes the walker's avoid areas, and a path's links the api's. */
class WalkAvoidAreasTest {

    @Test
    void avoidAreas_mapsEachRectangleFieldForField() {
        WorldPathConfig config = WorldPathConfig.builder()
                .avoid(new RectangularArea(3200, 3210, 3215, 3225, 1),
                        new CircularArea(new Coordinate(3000, 3000, 0), 2))
                .build();

        List<WwArea> areas = GameAPIImpl.avoidAreas(config);

        assertEquals(List.of(new WwArea(1, 3200, 3210, 3215, 3225),
                new WwArea(0, 2998, 2998, 3002, 3002)), areas);
    }

    @Test
    void defaultConfig_avoidsNothing_soTheOldEntryPointsAreUsed() {
        assertTrue(GameAPIImpl.avoidAreas(WorldPathConfig.DEFAULT).isEmpty());
    }

    @Test
    void toPathResult_carriesLinksAndWhetherTheyResolved() {
        RouteLink boat = new RouteLink(1, 42L, 0, 4, 0, 2412, new Coordinate(3029, 3217, 0),
                new Coordinate(2956, 3146, 0), "", "");
        WwPathResult path = new WwPathResult(List.of(
                new WwStep(StepKind.WALK, 0, 3029, 3217, 0),
                new WwStep(StepKind.TRANSITION, 0, 3029, 3217, 42)), 9f, List.of(boat), true);

        PathResult r = GameAPIImpl.toPathResult(path);

        assertTrue(r.found());
        assertEquals(2, r.pathLength());
        assertArrayEquals(new int[] {3029, 3217}, r.path().get(1));
        assertEquals(List.of(boat), r.links());
        assertTrue(r.linksResolved());
    }

    @Test
    void toPathResult_withoutLinkSupport_saysSo() {
        PathResult r = GameAPIImpl.toPathResult(new WwPathResult(List.of(), 0f));

        assertTrue(r.found());
        assertFalse(r.linksResolved());
        assertFalse(GameAPIImpl.toPathResult(null).found());
    }
}
