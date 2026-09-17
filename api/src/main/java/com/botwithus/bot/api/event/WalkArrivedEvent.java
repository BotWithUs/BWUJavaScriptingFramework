package com.botwithus.bot.api.event;

/**
 * Fired when the walker reaches its destination.
 *
 * @param targetX   the destination world X coordinate
 * @param targetY   the destination world Y coordinate
 * @param timestamp event creation time in milliseconds since epoch
 */
public record WalkArrivedEvent(int targetX, int targetY, long timestamp) implements GameEvent {

    public WalkArrivedEvent(int targetX, int targetY) {
        this(targetX, targetY, System.currentTimeMillis());
    }
}
