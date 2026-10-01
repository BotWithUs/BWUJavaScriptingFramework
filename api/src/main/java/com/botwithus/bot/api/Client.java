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
     * The display name of the character logged in on this client, once the host knows it.
     *
     * <p>This is the in-game character name the client reports for the logged-in player,
     * never the launcher's name for the account. It is empty at the login screen and in
     * the lobby, empty for a short while after entering a world until the host has read
     * the name, and empty again after a logout. A later login, including as a different
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
