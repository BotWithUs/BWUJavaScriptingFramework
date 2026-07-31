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

    /**
     * Whether the user (or the host) has asked this script to stop.
     *
     * <p>Poll this inside any loop or wait that could run for more than a
     * moment, and return from {@code onLoop} when it goes true. The runtime
     * also interrupts the script thread, but interruption is only observed
     * between loops — a script that waits <em>inside</em> {@code onLoop} won't
     * see it:</p>
     *
     * <pre>{@code
     * @Override
     * public int onLoop() {
     *     while (!bank.isOpen()) {
     *         if (ctx.isStopRequested()) {
     *             return -1;          // clean exit
     *         }
     *         Thread.sleep(100);
     *     }
     *     ...
     * }
     * }</pre>
     *
     * <p>Ignoring this is not fatal but is not free either: a script that won't
     * exit is eventually revoked — every subsequent game call throws
     * {@link com.botwithus.bot.api.runtime.ScriptRevokedException} — and then
     * quarantined, which keeps its thread alive and its classloader pinned for
     * the rest of the session.</p>
     *
     * <p>Defaults to {@code false} for contexts that aren't runtime-backed
     * (test mocks), so a script polling it in a unit test simply never stops.</p>
     */
    default boolean isStopRequested() {
        return false;
    }
}
