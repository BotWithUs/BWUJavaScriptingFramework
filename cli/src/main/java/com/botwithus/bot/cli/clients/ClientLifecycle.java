package com.botwithus.bot.cli.clients;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where a client is in its life, as the host sees it.
 *
 * <p>A pipe opens {@link Identifying}; once the host has read its account it is
 * {@link Connected}, or {@link Resuming} for a moment first when the account was
 * already known, typically after its game was restarted. A dropped pipe is
 * {@link NotResponding} while the host retries, and {@link Closed} once it is
 * gone for good. A closed client on a real account stays, remembered, until it
 * comes back or the user forgets it.</p>
 */
public sealed interface ClientLifecycle {

    /** Whether the client's pipe is open right now. */
    default boolean isOpen() {
        return switch (this) {
            case Identifying _, Connected _, Resuming _ -> true;
            case NotResponding _, Closed _ -> false;
        };
    }

    /** A pipe is open, but the host has not read the client's account yet. */
    record Identifying() implements ClientLifecycle { }

    /** The pipe is open and the client identified. */
    record Connected() implements ClientLifecycle { }

    /**
     * A client the host already knew came back on a new pipe. Shown for a moment
     * after it identifies, then it is {@link Connected}.
     *
     * @param previousPipe the pipe it was last seen on; empty when it was
     *                     remembered from an earlier run of the host
     * @param since        when it came back
     */
    record Resuming(Optional<String> previousPipe, Instant since) implements ClientLifecycle {
        public Resuming {
            Objects.requireNonNull(previousPipe, "previousPipe");
            Objects.requireNonNull(since, "since");
        }
    }

    /**
     * The pipe dropped and the client might come back: the host is retrying, or
     * was stopped from retrying while the client's game is still running.
     *
     * @param attempt     the retry in progress or last made, from {@code 1};
     *                    {@code 0} before the first
     * @param nextDelay   how long after {@code since} the next retry runs; empty
     *                    when nothing is retrying any more
     * @param maxAttempts the retry budget; empty when retries are unlimited
     * @param since       when the host reported this state
     */
    record NotResponding(int attempt, Optional<Duration> nextDelay, OptionalInt maxAttempts, Instant since)
            implements ClientLifecycle {
        public NotResponding {
            Objects.requireNonNull(nextDelay, "nextDelay");
            Objects.requireNonNull(maxAttempts, "maxAttempts");
            Objects.requireNonNull(since, "since");
        }

        /** Whether a retry is still to come. */
        public boolean isRetrying() {
            return nextDelay.isPresent();
        }

        /** When the next retry runs; empty when nothing is retrying. */
        public Optional<Instant> nextAttemptAt() {
            return nextDelay.map(since::plus);
        }
    }

    /**
     * The client is gone: its pipe was closed, or its game exited so the pipe can
     * never come back.
     *
     * @param since when the host saw it go, or, for a client remembered from an
     *              earlier run, when that run last saw it
     */
    record Closed(Instant since) implements ClientLifecycle {
        public Closed {
            Objects.requireNonNull(since, "since");
        }
    }
}
