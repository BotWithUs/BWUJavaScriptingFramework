package com.botwithus.bot.cli.clients;

import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.events.ClientKey;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One client as the {@link ClientRegistry} saw it when the snapshot was taken.
 * Immutable: later changes to the client never show up in it.
 *
 * @param key         the client's key; see {@link ClientKey}
 * @param lifecycle   where the client is in its life
 * @param pipe        the pipe the client is on; empty once it has closed.
 *                    A client whose game exited keeps its dead pipe here until
 *                    the host drops the connection, so it can still be forgotten
 * @param name        the best name the client shows, else the last one it showed
 * @param gameStatus  game state, world and membership while the client has a
 *                    connection; {@link GameStatus#UNKNOWN} once it has closed
 * @param lastWorld   the world the client is in, else the last one it was seen in
 * @param connectedAt when the host connected to the client's current pipe; empty
 *                    once it has closed
 */
public record ClientRecord(ClientKey key, ClientLifecycle lifecycle, Optional<String> pipe,
                           Optional<String> name, GameStatus gameStatus, OptionalInt lastWorld,
                           Optional<Instant> connectedAt) {

    public ClientRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(gameStatus, "gameStatus");
        Objects.requireNonNull(lastWorld, "lastWorld");
        Objects.requireNonNull(connectedAt, "connectedAt");
    }

    /**
     * Whether the host keeps this client after it closes and across restarts:
     * only a client on a real account, and only the first one open on it.
     */
    public boolean isRemembered() {
        return key.isRemembered();
    }
}
