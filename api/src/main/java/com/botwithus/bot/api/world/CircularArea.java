package com.botwithus.bot.api.world;

import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Every tile whose straight-line distance from the centre is at most the radius.
 *
 * @param centre the centre tile; its plane is the area's plane
 * @param radius radius in tiles, {@code >= 0}
 */
public record CircularArea(Coordinate centre, int radius) implements Area {

    /**
     * @throws IllegalArgumentException when {@code radius} is negative
     */
    public CircularArea {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0, got " + radius);
        }
    }

    @Override
    public int plane() {
        return centre.plane();
    }

    /** The square around the circle. */
    @Override
    public Optional<RectangularArea> boundingBox() {
        return Optional.of(RectangularArea.around(centre, radius));
    }

    @Override
    public boolean contains(int x, int y, int tilePlane) {
        long dx = x - centre.x();
        long dy = y - centre.y();
        return tilePlane == centre.plane() && dx * dx + dy * dy <= (long) radius * radius;
    }

    /**
     * A uniformly random tile of the circle, drawn from its bounding square and redrawn
     * until it lands inside. At small radii just under half the draws miss; the loop ends
     * because the centre tile is always inside.
     */
    @Override
    public Coordinate randomTile(RandomGenerator random) {
        RectangularArea bounds = RectangularArea.around(centre, radius);
        Coordinate tile = bounds.randomTile(random);
        while (!contains(tile)) {
            tile = bounds.randomTile(random);
        }
        return tile;
    }
}
