package com.botwithus.bot.api.event;

/**
 * Fired when the client login state changes (e.g., lobby to logged in).
 *
 * <p>Common states: {@code 10} = lobby, {@code 20} = loading, {@code 30} = logged in.</p>
 *
 * @param oldState  the previous login state value
 * @param newState  the new login state value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record LoginStateChangeEvent(int oldState, int newState, long timestamp) implements GameEvent {

    public LoginStateChangeEvent(int oldState, int newState) {
        this(oldState, newState, System.currentTimeMillis());
    }
}
