package com.botwithus.bot.core.impl;

/**
 * The rectangle of tiles a placed loc covers: its south-west anchor tile plus its cache size,
 * with the two dimensions swapped for a loc rotated a quarter or three quarters of a turn.
 *
 * <p>Distances are Chebyshev distances from a tile to the nearest tile of the rectangle, so a
 * tile inside the footprint is at distance 0 and a tile touching it on any side or corner is at
 * distance 1.</p>
 *
 * @param minX  the anchor (south-west) tile's x
 * @param minY  the anchor (south-west) tile's y
 * @param width the footprint's extent along x, at least one tile
 * @param depth the footprint's extent along y, at least one tile
 */
record LocFootprint(int minX, int minY, int width, int depth) {

    /** Mask that is set for the two rotations (1 and 3) that turn a loc on its side. */
    private static final int QUARTER_TURN_BIT = 1;

    /** Size used when a loc's cache entry cannot be read: its anchor tile alone. */
    static final int UNKNOWN_SIZE = 1;

    /** Rejects a footprint narrower than one tile, which no placed loc can have. */
    LocFootprint {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException(
                    "footprint must be at least 1x1: " + width + "x" + depth);
        }
    }

    /**
     * Builds the footprint of a loc anchored at ({@code x}, {@code y}) with cache size
     * ({@code sizeX}, {@code sizeY}) placed at {@code rotation} (0..3). A non-positive size, which
     * a missing or sentinel cache entry reports, is read as one tile.
     */
    static LocFootprint of(int x, int y, int sizeX, int sizeY, int rotation) {
        int sx = Math.max(sizeX, UNKNOWN_SIZE);
        int sy = Math.max(sizeY, UNKNOWN_SIZE);
        boolean isTurned = (rotation & QUARTER_TURN_BIT) != 0;
        return isTurned ? new LocFootprint(x, y, sy, sx) : new LocFootprint(x, y, sx, sy);
    }

    /** Chebyshev distance from ({@code x}, {@code y}) to the nearest tile of this footprint. */
    int distanceTo(int x, int y) {
        int dx = axisGap(x, minX, minX + width - 1);
        int dy = axisGap(y, minY, minY + depth - 1);
        return Math.max(dx, dy);
    }

    /** How far {@code v} lies outside the closed interval [{@code lo}, {@code hi}]. */
    private static int axisGap(int v, int lo, int hi) {
        if (v < lo) {
            return lo - v;
        }
        return v > hi ? v - hi : 0;
    }
}
