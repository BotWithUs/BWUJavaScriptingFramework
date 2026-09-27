package com.botwithus.bot.cli.events;

import java.util.Objects;

/**
 * Names the client a {@link HostEvent.ClientEvent} is about: the key it is
 * known by, and the pipe the event came through.
 *
 * <p>The key is the one the client had when the event was published. It is a
 * pipe key until the host has read the client's account, and the account's key
 * from the {@link HostEvent.ClientIdentified} on; that event is where a
 * subscriber that keeps state per client moves it from one key to the other.
 * Events are keyed as they are published, in the same order, so no event about
 * a pipe is published under its old key after the identification.</p>
 *
 * @param key  the client's key, never {@code null}
 * @param pipe the pipe the client is on, never {@code null}; {@link #NO_PIPE} for
 *             a client the host has not seen on a pipe since it started, such as
 *             one remembered from an earlier run
 */
public record ClientRef(ClientKey key, String pipe) {

    /** The {@link #pipe()} of a client with no pipe this session. */
    public static final String NO_PIPE = "";

    public ClientRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pipe, "pipe");
    }

    /** A client on {@code pipe} whose account is not known: keyed by the pipe. */
    public ClientRef(String pipe) {
        this(ClientKey.pipe(pipe), pipe);
    }

    /** Whether the client was on a pipe this session. */
    public boolean hasPipe() {
        return !pipe.isEmpty();
    }
}
