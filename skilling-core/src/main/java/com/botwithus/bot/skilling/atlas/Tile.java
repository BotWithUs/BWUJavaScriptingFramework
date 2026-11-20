package com.botwithus.bot.skilling.atlas;

import com.botwithus.bot.api.world.Coordinate;

/**
 * A world tile (RS3 CoordGrid) — the coordinate space gather/bank/npc rows use. The same
 * value as the api's {@link Coordinate}, which a script should prefer; this record stays
 * because the Atlas rows decode into it, and {@link #toCoordinate()} / {@link #of(Coordinate)}
 * convert between the two.
 */
public record Tile(int x, int y, int plane) {

    /** The api's {@link Coordinate} for this tile. */
    public Coordinate toCoordinate() {
        return new Coordinate(x, y, plane);
    }

    /** The Atlas tile for an api {@link Coordinate}. */
    public static Tile of(Coordinate coordinate) {
        return new Tile(coordinate.x(), coordinate.y(), coordinate.plane());
    }

    /** Chebyshev (king-move) distance to another tile's x/y, ignoring plane. */
    public int chebyshev(int otherX, int otherY) {
        return Math.max(Math.abs(x - otherX), Math.abs(y - otherY));
    }
}
