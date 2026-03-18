package com.botwithus.bot.api.event;

/**
 * Fired when a walk is cancelled (explicitly via {@code walkCancel()}, or
 * implicitly when an action interrupts it).
 */
public class WalkCancelledEvent extends GameEvent {
    private final int targetX;
    private final int targetY;

    public WalkCancelledEvent(int targetX, int targetY) {
        super("walk_cancelled");
        this.targetX = targetX;
        this.targetY = targetY;
    }

    /** Returns the original target world X coordinate. */
    public int getTargetX() { return targetX; }

    /** Returns the original target world Y coordinate. */
    public int getTargetY() { return targetY; }
}
