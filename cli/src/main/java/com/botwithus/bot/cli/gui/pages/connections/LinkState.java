package com.botwithus.bot.cli.gui.pages.connections;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where one row of the Connections table is in its pipe's life, with the
 * numbers the Link column and the detail pane show for it.
 */
public sealed interface LinkState {

    /** The pipe is open and the account has been read. */
    record Connected() implements LinkState { }

    /** The pipe is open; the host is still reading the account. */
    record Identifying() implements LinkState { }

    /**
     * A known account came back on a new pipe a moment ago.
     *
     * @param previousPipe the pipe it was last seen on; empty when it was
     *                     remembered from an earlier run of the host
     */
    record Resuming(Optional<String> previousPipe) implements LinkState {
        public Resuming {
            Objects.requireNonNull(previousPipe, "previousPipe");
        }
    }

    /**
     * The pipe dropped. The host is retrying, or was told to stop.
     *
     * @param attempt     the retry in progress or last made, from {@code 1};
     *                    {@code 0} before the first
     * @param maxAttempts the retry budget; empty when there is no limit
     * @param nextIn      how long until the next retry; empty when nothing is
     *                    retrying any more
     * @param downFor     how long the client has not answered
     */
    record NotResponding(int attempt, OptionalInt maxAttempts, Optional<Duration> nextIn, Duration downFor)
            implements LinkState {
        public NotResponding {
            Objects.requireNonNull(maxAttempts, "maxAttempts");
            Objects.requireNonNull(nextIn, "nextIn");
            Objects.requireNonNull(downFor, "downFor");
        }

        /** Whether another retry is still to come. */
        public boolean isRetrying() {
            return nextIn.isPresent();
        }
    }

    /** A scan saw the pipe; the host is not connected to it. */
    record Found() implements LinkState { }

    /**
     * The client is gone.
     *
     * @param ago          how long since the host saw it go
     * @param isClientGone its game exited while the host still held the pipe, so
     *                     retrying can never work: the only way on is Forget
     */
    record Closed(Duration ago, boolean isClientGone) implements LinkState {
        public Closed {
            Objects.requireNonNull(ago, "ago");
        }
    }
}
