package com.botwithus.bot.api.world;

import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * An axis-aligned rectangle of tiles, both corners included.
 *
 * @param minX  lowest x in the area
 * @param minY  lowest y in the area
 * @param maxX  highest x in the area
 * @param maxY  highest y in the area
 * @param plane the plane
 */
public record RectangularArea(int minX, int minY, int maxX, int maxY, int plane) implements Area {

    /**
     * @throws IllegalArgumentException when a min exceeds its max, or the plane is invalid
     */
    public RectangularArea {
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("min corner (" + minX + "," + minY
                    + ") is past max corner (" + maxX + "," + maxY + ")");
        }
        if (plane < 0 || plane > Coordinate.MAX_PLANE) {
            throw new IllegalArgumentException("plane must be 0.." + Coordinate.MAX_PLANE);
        }
    }

    /**
     * The rectangle spanning two opposite corners, given in either order.
     *
     * @throws IllegalArgumentException when the corners are on different planes
     */
    public static RectangularArea of(Coordinate corner, Coordinate opposite) {
        if (corner.plane() != opposite.plane()) {
            throw new IllegalArgumentException("corners are on planes " + corner.plane()
                    + " and " + opposite.plane());
        }
        return new RectangularArea(Math.min(corner.x(), opposite.x()),
                Math.min(corner.y(), opposite.y()), Math.max(corner.x(), opposite.x()),
                Math.max(corner.y(), opposite.y()), corner.plane());
    }

    /** The square of tiles within {@code radius} (Chebyshev) of {@code centre}. */
    public static RectangularArea around(Coordinate centre, int radius) {
        return of(centre.offset(-radius, -radius), centre.offset(radius, radius));
    }

    /** This rectangle itself. */
    @Override
    public Optional<RectangularArea> boundingBox() {
        return Optional.of(this);
    }

    @Override
    public boolean contains(int x, int y, int tilePlane) {
        return tilePlane == plane && x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    @Override
    public Coordinate randomTile(RandomGenerator random) {
        return new Coordinate(random.nextInt(minX, maxX + 1), random.nextInt(minY, maxY + 1), plane);
    }

    /** Tiles across, both edges included. */
    public int width() {
        return maxX - minX + 1;
    }

    /** Tiles down, both edges included. */
    public int height() {
        return maxY - minY + 1;
    }
}
