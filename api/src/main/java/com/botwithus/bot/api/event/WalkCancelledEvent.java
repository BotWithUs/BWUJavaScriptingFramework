package com.botwithus.bot.api.event;

/**
 * Fired when a walk is cancelled (explicitly via {@code walkCancel()}, or
 * implicitly when an action interrupts it).
 *
 * @param targetX   the original target world X coordinate
 * @param targetY   the original target world Y coordinate
 * @param timestamp event creation time in milliseconds since epoch
 */
public record WalkCancelledEvent(int targetX, int targetY, long timestamp) implements GameEvent {

    public WalkCancelledEvent(int targetX, int targetY) {
        this(targetX, targetY, System.currentTimeMillis());
    }
}
