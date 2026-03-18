package com.botwithus.bot.api.model;

/**
 * An agility shortcut in the navigation graph.
 *
 * @param objectId    game object ID
 * @param x           world tile X
 * @param y           world tile Y
 * @param plane       plane
 * @param shape       object shape
 * @param rotation    object rotation
 * @param agilityLevel required agility level
 */
public record NavShortcut(
        int objectId, int x, int y, int plane,
        int shape, int rotation, int agilityLevel
) {
    /** Creates a shortcut with default plane=0, shape=0, rotation=0. */
    public NavShortcut(int objectId, int x, int y, int agilityLevel) {
        this(objectId, x, y, 0, 0, 0, agilityLevel);
    }
}
