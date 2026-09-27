package com.botwithus.bot.cli.clients;

import com.botwithus.bot.cli.GameStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the {@link ClientRegistry} reads from a client's connection while it has
 * one. Read each time a snapshot is taken, so a snapshot shows the connection as
 * it is, not as it was when an event arrived. Every method must be quick and
 * safe to call from any thread.
 */
public interface LiveClient {

    /** When the host connected to the client's current pipe. */
    Instant connectedAt();

    /** The best name the client shows; empty until one is known. */
    Optional<String> displayName();

    /** Game state, world and membership, as last read. */
    GameStatus gameStatus();

    /** The reconnect budget; empty when retries are unlimited. */
    OptionalInt maxAttempts();

    /**
     * Whether reconnecting stopped because the client's game exited, so its pipe
     * can never come back.
     */
    boolean isClientGone();
}
