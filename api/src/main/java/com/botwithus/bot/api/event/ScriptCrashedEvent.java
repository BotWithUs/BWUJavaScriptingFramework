package com.botwithus.bot.api.event;

import com.botwithus.bot.api.runtime.LastCrash;

/**
 * Fired by the runtime when a script's lifecycle hook throws. The producer
 * (the {@code ScriptRunner}) builds the {@link LastCrash} payload describing
 * which phase failed and the underlying throwable, then publishes this event
 * once via the per-connection {@code EventBus}.
 *
 * @param scriptName     the script's manifest name (or class simple name)
 * @param connectionName the originating connection, or {@code null} for runners
 *                       not yet bound to a connection
 * @param crash          the structured crash description
 * @param timestamp      event creation time in milliseconds since epoch
 */
public record ScriptCrashedEvent(String scriptName, String connectionName,
                                 LastCrash crash, long timestamp)
        implements GameEvent {

    public ScriptCrashedEvent(String scriptName, String connectionName, LastCrash crash) {
        this(scriptName, connectionName, crash, System.currentTimeMillis());
    }
}
