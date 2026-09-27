package com.botwithus.bot.api.event;

/**
 * Fired when the client login state changes (e.g., lobby to logged in).
 *
 * <p>Settled states: {@code 10} = login screen, {@code 20} = lobby, {@code 30} = in a world.
 * Other codes are not settled states; {@code 0} means the agent could not resolve
 * the client yet.</p>
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
