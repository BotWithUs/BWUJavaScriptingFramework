package com.botwithus.bot.api.event;

/**
 * Fired when the walker reaches its destination.
 */
public class WalkArrivedEvent extends GameEvent {
    private final int targetX;
    private final int targetY;

    public WalkArrivedEvent(int targetX, int targetY) {
        super("walk_arrived");
        this.targetX = targetX;
        this.targetY = targetY;
    }

    /** Returns the destination world X coordinate. */
    public int getTargetX() { return targetX; }

    /** Returns the destination world Y coordinate. */
    public int getTargetY() { return targetY; }
}
