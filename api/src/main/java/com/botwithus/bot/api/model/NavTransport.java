package com.botwithus.bot.api.model;

/**
 * A transport link in the navigation graph.
 *
 * @param objectId    game object ID
 * @param x           source world tile X
 * @param y           source world tile Y
 * @param plane       source plane
 * @param shape       object shape
 * @param rotation    object rotation
 * @param optionIndex interaction option index
 * @param destX       destination world tile X
 * @param destY       destination world tile Y
 * @param destPlane   destination plane
 */
public record NavTransport(
        int objectId, int x, int y, int plane,
        int shape, int rotation, int optionIndex,
        int destX, int destY, int destPlane
) {
    /** Creates a transport with default plane=0, shape=10, rotation=0, optionIndex=0, destPlane=0. */
    public NavTransport(int objectId, int x, int y, int destX, int destY) {
        this(objectId, x, y, 0, 10, 0, 0, destX, destY, 0);
    }
}
