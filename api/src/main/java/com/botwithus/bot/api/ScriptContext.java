package com.botwithus.bot.api;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ScriptManager;

/**
 * Context object passed to {@link BotScript#onStart} providing access to
 * the game API and the event bus.
 *
 * @see BotScript
 * @see GameAPI
 * @see EventBus
 * @see MessageBus
 */
public interface ScriptContext {

    /**
     * Returns the game API for interacting with the game client.
     *
     * @return the {@link GameAPI} instance
     */
    GameAPI getGameAPI();

    /**
     * Returns the event bus for subscribing to and publishing game events.
     *
     * @return the {@link EventBus} instance
     */
    EventBus getEventBus();

    /**
     * Returns the message bus for inter-script communication.
     *
     * @return the {@link MessageBus} instance
     */
    MessageBus getMessageBus();

    /**
     * Returns the client provider for accessing all connected game clients.
     *
     * @return the {@link ClientProvider} instance
     */
    ClientProvider getClientProvider();

    /**
     * Returns the shared state store for inter-script data sharing.
     *
     * @return the {@link SharedState} instance
     */
    SharedState getSharedState();

    /**
     * Returns the script manager for starting, stopping, and scheduling other scripts.
     *
     * @return the {@link ScriptManager} instance
     */
    ScriptManager getScriptManager();

    /**
     * Returns the navigation interface for blocking walk operations.
     * Walk methods block the calling thread until arrival, cancellation,
     * failure, or timeout, but do not block the pipe.
     *
     * @return the {@link Navigation} instance
     */
    Navigation getNavigation();
}
