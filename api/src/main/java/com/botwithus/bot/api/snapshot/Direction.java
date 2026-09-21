package com.botwithus.bot.api.snapshot;

/**
 * The eight compass points, in clockwise order starting at north.
 *
 * <p>The declaration order is load-bearing: {@link Orientation#compass()} indexes
 * {@link #values()} by 45-degree sector, so reordering these constants changes what
 * every script reads.</p>
 */
public enum Direction {
    NORTH,
    NORTH_EAST,
    EAST,
    SOUTH_EAST,
    SOUTH,
    SOUTH_WEST,
    WEST,
    NORTH_WEST;

    /** Degrees each point covers: a full turn split eight ways. */
    static final double SECTOR_DEGREES = 45.0;

    /** Compass bearing of this point, clockwise from north: {@code 0, 45, ... 315}. */
    public double degrees() {
        return ordinal() * SECTOR_DEGREES;
    }
}
