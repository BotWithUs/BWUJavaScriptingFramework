package com.botwithus.bot.cli.events;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;

import java.time.Instant;

/**
 * Copies the host-relevant events from one connection's game event bus onto the
 * {@link HostEventBus}: the pipe dropping, the reconnect state changing, and a
 * script crashing. Those are published per connection, where nothing outside
 * that connection's subscribers would see them.
 */
public final class GameEventBridge {

    private final ClientKeys keys;

    /** @param keys keys each event by the client it came from, and publishes it */
    public GameEventBridge(ClientKeys keys) {
        this.keys = keys;
    }

    /**
     * Subscribes to {@code connectionBus} for the life of the connection. The
     * events are attributed to {@code pipe}, the connection that owns the bus.
     */
    public void attach(EventBus connectionBus, String pipe) {
        connectionBus.subscribe(ConnectionLostEvent.class, e -> keys.publish(pipe,
                client -> new ConnectionLost(client, e.cause(), at(e.timestamp()))));
        connectionBus.subscribe(ReconnectStateChangedEvent.class, e -> keys.publish(pipe,
                client -> new ReconnectStateChanged(client, e.state(), at(e.timestamp()))));
        connectionBus.subscribe(ScriptCrashedEvent.class, e -> keys.publish(pipe,
                client -> new ScriptCrashed(client, e.scriptName(), e.crash(), at(e.timestamp()))));
    }

    private static Instant at(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }
}
