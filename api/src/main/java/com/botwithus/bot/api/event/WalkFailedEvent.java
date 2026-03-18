package com.botwithus.bot.api.event;

/**
 * Fired when a walk fails (pathfinding failure, stuck timeout, context loss, etc.).
 */
public class WalkFailedEvent extends GameEvent {
    private final int targetX;
    private final int targetY;

    public WalkFailedEvent(int targetX, int targetY) {
        super("walk_failed");
        this.targetX = targetX;
        this.targetY = targetY;
    }

    /** Returns the intended target world X coordinate. */
    public int getTargetX() { return targetX; }

    /** Returns the intended target world Y coordinate. */
    public int getTargetY() { return targetY; }
}
