package com.botwithus.bot.skilling.atlas;

/** A world tile (RS3 CoordGrid) — the coordinate space gather/bank/npc rows use. */
public record Tile(int x, int y, int plane) {

    /** Chebyshev (king-move) distance to another tile's x/y, ignoring plane. */
    public int chebyshev(int otherX, int otherY) {
        return Math.max(Math.abs(x - otherX), Math.abs(y - otherY));
    }
}
