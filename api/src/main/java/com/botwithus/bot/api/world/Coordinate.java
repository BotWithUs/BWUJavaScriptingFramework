package com.botwithus.bot.api.world;

import com.botwithus.bot.api.entities.EntityContext;

/**
 * An immutable world tile: absolute x and y, and the plane. It is an
 * {@link EntityContext}, so it goes anywhere an entity's position does — a distance, an
 * {@link Area#contains(EntityContext)}, an {@code EntityQuery.nearestTo(...)}.
 *
 * <p>Distances ignore the plane, as the game's own range checks do; compare
 * {@link #plane()} yourself when the floor matters.</p>
 *
 * @param x     absolute world tile x
 * @param y     absolute world tile y
 * @param plane height level, {@code 0..3}
 */
public record Coordinate(int x, int y, int plane) implements EntityContext {

    /** Tiles along one side of a map region (a "mapsquare"). */
    public static final int REGION_SIZE = 64;

    /** Bits a tile coordinate shifts down by to give its region coordinate. */
    private static final int REGION_SHIFT = 6;

    /** Bits the region x shifts up by in a {@link #regionId()}. */
    private static final int REGION_ID_X_SHIFT = 8;

    /** Mask for a tile's offset inside its region. */
    private static final int REGION_LOCAL_MASK = REGION_SIZE - 1;

    /** Highest plane the game has. */
    public static final int MAX_PLANE = 3;

    /**
     * @throws IllegalArgumentException when {@code plane} is outside {@code 0..3}
     */
    public Coordinate {
        if (plane < 0 || plane > MAX_PLANE) {
            throw new IllegalArgumentException("plane must be 0.." + MAX_PLANE + ", got " + plane);
        }
    }

    /** The tile an entity stands on. */
    public static Coordinate of(EntityContext entity) {
        return new Coordinate(entity.tileX(), entity.tileY(), entity.plane());
    }

    @Override
    public int tileX() {
        return x;
    }

    @Override
    public int tileY() {
        return y;
    }

    /** Chebyshev (king-move) distance, the metric the game uses for "within N tiles". */
    public int chebyshevDistance(EntityContext other) {
        return distanceTo(other);
    }

    /** Straight-line distance in tiles. */
    public double euclideanDistance(EntityContext other) {
        return Math.hypot(x - other.tileX(), y - other.tileY());
    }

    /** The region ("mapsquare") id the game uses: {@code regionX << 8 | regionY}. */
    public int regionId() {
        return regionX() << REGION_ID_X_SHIFT | regionY();
    }

    /** Region column, {@code x / 64}. */
    public int regionX() {
        return x >> REGION_SHIFT;
    }

    /** Region row, {@code y / 64}. */
    public int regionY() {
        return y >> REGION_SHIFT;
    }

    /** This tile's x inside its region, {@code 0..63}. */
    public int regionLocalX() {
        return x & REGION_LOCAL_MASK;
    }

    /** This tile's y inside its region, {@code 0..63}. */
    public int regionLocalY() {
        return y & REGION_LOCAL_MASK;
    }

    /** The tile {@code dx}, {@code dy} away on the same plane. */
    public Coordinate offset(int dx, int dy) {
        return new Coordinate(x + dx, y + dy, plane);
    }

    /** The tile {@code dx}, {@code dy}, {@code dPlane} away. */
    public Coordinate offset(int dx, int dy, int dPlane) {
        return new Coordinate(x + dx, y + dy, plane + dPlane);
    }

    /** This tile's x and y on another plane. */
    public Coordinate withPlane(int newPlane) {
        return new Coordinate(x, y, newPlane);
    }
}
