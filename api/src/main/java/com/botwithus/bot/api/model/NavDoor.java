package com.botwithus.bot.api.model;

/**
 * A door in the navigation graph.
 *
 * @param objectId game object ID
 * @param x        world tile X
 * @param y        world tile Y
 * @param plane    plane
 * @param shape    object shape
 * @param rotation object rotation
 */
public record NavDoor(
        int objectId, int x, int y, int plane,
        int shape, int rotation
) {
    /** Creates a door with default plane=0, shape=0, rotation=0. */
    public NavDoor(int objectId, int x, int y) {
        this(objectId, x, y, 0, 0, 0);
    }
}
