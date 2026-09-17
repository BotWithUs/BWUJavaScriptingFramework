package com.botwithus.bot.api.event;

/**
 * Fired when a break countdown completes and the bot resumes.
 *
 * @param timestamp event creation time in milliseconds since epoch
 */
public record BreakEndedEvent(long timestamp) implements GameEvent {

    public BreakEndedEvent() {
        this(System.currentTimeMillis());
    }
}
