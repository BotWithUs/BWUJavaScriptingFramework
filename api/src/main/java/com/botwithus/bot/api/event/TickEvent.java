package com.botwithus.bot.api.event;

/**
 * Fired each game tick when the server tick counter advances.
 *
 * @param tick      the server tick counter value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record TickEvent(int tick, long timestamp) implements GameEvent {

    public TickEvent(int tick) {
        this(tick, System.currentTimeMillis());
    }
}
