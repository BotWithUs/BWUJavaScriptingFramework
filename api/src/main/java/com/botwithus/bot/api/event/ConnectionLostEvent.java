package com.botwithus.bot.api.event;

/**
 * Fired when the underlying pipe transport drops mid-session. The producer
 * (the {@code ReconnectController}) publishes this exactly once per
 * disconnect, before transitioning the {@code ReconnectState} machine to
 * {@code Disconnected}/{@code Reconnecting}.
 *
 * @param connectionName the originating connection name
 * @param cause          the throwable that surfaced the disconnect, or
 *                       {@code null} if the disconnect was inferred without
 *                       an exception (e.g. EOF on a clean shutdown)
 * @param timestamp      event creation time in milliseconds since epoch
 */
public record ConnectionLostEvent(String connectionName, Throwable cause, long timestamp)
        implements GameEvent {

    public ConnectionLostEvent(String connectionName, Throwable cause) {
        this(connectionName, cause, System.currentTimeMillis());
    }
}
