package com.botwithus.bot.api.event;

import com.botwithus.bot.api.runtime.ReconnectState;

/**
 * Fired whenever the {@code ReconnectController} transitions between
 * {@link ReconnectState} variants. The notification overlay subscribes to
 * this event to render banners during reconnect activity.
 *
 * @param connectionName the originating connection name
 * @param state          the new state — exhaustively switchable
 * @param timestamp      event creation time in milliseconds since epoch
 */
public record ReconnectStateChangedEvent(String connectionName, ReconnectState state, long timestamp)
        implements GameEvent {

    public ReconnectStateChangedEvent(String connectionName, ReconnectState state) {
        this(connectionName, state, System.currentTimeMillis());
    }
}
