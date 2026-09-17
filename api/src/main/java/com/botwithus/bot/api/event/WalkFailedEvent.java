package com.botwithus.bot.api.event;

/**
 * Fired when a walk fails (pathfinding failure, stuck timeout, context loss, etc.).
 *
 * @param targetX   the intended target world X coordinate
 * @param targetY   the intended target world Y coordinate
 * @param timestamp event creation time in milliseconds since epoch
 */
public record WalkFailedEvent(int targetX, int targetY, long timestamp) implements GameEvent {

    public WalkFailedEvent(int targetX, int targetY) {
        this(targetX, targetY, System.currentTimeMillis());
    }
}
