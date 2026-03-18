package com.botwithus.bot.api.model;

/**
 * A plane (floor level) transition in the navigation graph.
 *
 * @param objectId  game object ID
 * @param x         world tile X
 * @param y         world tile Y
 * @param plane     source plane
 * @param shape     object shape
 * @param rotation  object rotation
 * @param sizeX     object size X
 * @param sizeY     object size Y
 * @param destX     destination X ({@code -1} to auto-compute)
 * @param destY     destination Y ({@code -1} to auto-compute)
 * @param destPlane destination plane
 */
public record NavPlaneTransition(
        int objectId, int x, int y, int plane,
        int shape, int rotation,
        int sizeX, int sizeY,
        int destX, int destY, int destPlane
) {
    /** Creates a plane transition with common defaults: plane=0, shape=10, rotation=0, size 1x1, auto-dest. */
    public NavPlaneTransition(int objectId, int x, int y, int destPlane) {
        this(objectId, x, y, 0, 10, 0, 1, 1, -1, -1, destPlane);
    }
}
