package com.botwithus.bot.api;

import com.botwithus.bot.api.event.EventBus;

/**
 * Represents a single connected game client with its associated API and event bus.
 */
public interface Client {

    /**
     * Returns the unique name identifying this client connection.
     *
     * @return the client name
     */
    String getName();

    /**
     * Returns the game API for this client.
     *
     * @return the {@link GameAPI} instance bound to this client
     */
    GameAPI getGameAPI();

    /**
     * Returns the event bus for this client.
     *
     * @return the {@link EventBus} instance bound to this client
     */
    EventBus getEventBus();

    /**
     * Checks whether this client is still connected to the game server.
     *
     * @return {@code true} if the connection is active
     */
    boolean isConnected();
}
