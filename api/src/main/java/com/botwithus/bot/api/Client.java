package com.botwithus.bot.api;

import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.Optional;

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

    /**
     * Returns a tick-scoped read view of the producer's current game state.
     * Each call returns a fresh snapshot bound to the producer's currently
     * published buffer; do not cache the result across ticks. Returns
     * {@code null} when this client was constructed without a shared-memory
     * binding (e.g. headless/test contexts).
     *
     * @return a {@link GameSnapshot}, or {@code null} when unbound
     */
    GameSnapshot snapshot();

    /**
     * The display name of the character on this client, once the host knows it.
     *
     * <p>When the client was launched for a known Jagex character, this is that
     * character's name from the login screen on, and through the lobby. Once in a world
     * it is the name the client reports for the logged-in player; if that differs from
     * the launched character (the client was relogged as someone else), the logged-in
     * name wins. Otherwise, for a client not launched for a known Jagex character, it is
     * empty until the client is in a world and the host has read the name, and empty
     * again after a logout. Either way it can be briefly empty right after a login state
     * change while the host re-reads it. A later login, including as a different
     * character, is picked up without reconnecting. Cheap to call: it never blocks on the
     * client.</p>
     *
     * <p>The default returns empty, for clients that are not bound to a live game client
     * (test and headless contexts).</p>
     *
     * @return the character's display name, or empty while it is not known
     */
    default Optional<String> getDisplayName() {
        return Optional.empty();
    }
}
