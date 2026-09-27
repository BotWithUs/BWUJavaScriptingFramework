package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ClientKey;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One client, as the daily summary reports it.
 *
 * @param key            the client's key, which its history is kept under
 * @param name           the name to show; never an account id
 * @param state          whether it is online now
 * @param connectedAt    when the host connected to its current pipe; empty once it closed
 * @param runningScripts scripts running on it now
 */
public record ClientSnapshot(ClientKey key, String name, State state, Optional<Instant> connectedAt,
                             int runningScripts) {

    /** Where a client is, for the summary. */
    public enum State {
        /** The pipe is open. */
        ONLINE,
        /** The pipe dropped and the host is retrying, or was stopped from retrying. */
        NOT_RESPONDING,
        /** The client is gone. */
        CLOSED
    }

    public ClientSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(connectedAt, "connectedAt");
    }
}
