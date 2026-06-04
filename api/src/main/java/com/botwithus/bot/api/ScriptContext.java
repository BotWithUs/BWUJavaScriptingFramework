package com.botwithus.bot.api;

import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;

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
     * Returns the shared state store for inter-script data sharing.
     *
     * @return the {@link SharedState} instance
     */
    SharedState getSharedState();

    /**
     * Returns the navigation interface for blocking walk operations.
     * Walk methods block the calling thread until arrival, cancellation,
     * failure, or timeout, but do not block the pipe.
     *
     * @return the {@link Navigation} instance
     */
    Navigation getNavigation();

    /**
     * Returns the debug publisher bound to this script. Calls into the returned
     * publisher fan out to whoever is subscribed to the agent's {@code script.context}
     * broker topic (primarily {@code NXTDebugger}).
     *
     * <p>The default implementation returns {@link ScriptContextPublisher#NOOP};
     * runtimes that have wired a channel override this to return a per-script
     * tagged publisher.</p>
     */
    default ScriptContextPublisher getScriptContext() {
        return ScriptContextPublisher.NOOP;
    }
}
