package com.botwithus.bot.cli.gui.usermode.board;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Where a client card's client is, as the card shows it: the host's
 * {@code ClientLifecycle} with the figures each state's card needs, measured
 * when the view was built.
 */
public sealed interface ClientState {

    /** A pipe is open, but the host has not read the account yet. */
    record Identifying() implements ClientState { }

    /**
     * Connected and identified.
     *
     * @param online   how long the host has been connected to the current pipe
     * @param rpcAvgMs the average RPC round trip; empty before the first call
     */
    record Connected(Duration online, OptionalDouble rpcAvgMs) implements ClientState {
        public Connected {
            Objects.requireNonNull(online, "online");
            Objects.requireNonNull(rpcAvgMs, "rpcAvgMs");
        }
    }

    /**
     * The pipe dropped and the host is retrying, or was told to stop.
     *
     * @param silentFor   how long the client has not answered
     * @param attempt     the retry in progress or last made, from {@code 1}; {@code 0} before the first
     * @param maxAttempts the retry budget; empty when retries are unlimited
     * @param nextIn      how long until the next retry; empty when nothing is retrying
     */
    record NotResponding(Duration silentFor, int attempt, OptionalInt maxAttempts, Optional<Duration> nextIn)
            implements ClientState {
        public NotResponding {
            Objects.requireNonNull(silentFor, "silentFor");
            Objects.requireNonNull(maxAttempts, "maxAttempts");
            Objects.requireNonNull(nextIn, "nextIn");
        }

        public boolean isRetrying() {
            return nextIn.isPresent();
        }
    }

    /**
     * The game client is gone. A card on a real account stays until the account
     * comes back or the user dismisses it.
     *
     * @param closedFor how long ago the host saw it go
     */
    record Closed(Duration closedFor) implements ClientState {
        public Closed {
            Objects.requireNonNull(closedFor, "closedFor");
        }
    }

    /** The same account came back on a new pipe; its scripts are being restarted. */
    record Resuming() implements ClientState { }

    /** Whether the client is connected and identified, so its scripts can be driven. */
    default boolean isConnected() {
        return switch (this) {
            case Connected _ -> true;
            case Identifying _, NotResponding _, Closed _, Resuming _ -> false;
        };
    }
}
