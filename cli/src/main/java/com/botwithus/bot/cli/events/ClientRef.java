package com.botwithus.bot.cli.events;

import java.util.Objects;

/**
 * Names the client a {@link HostEvent.ClientEvent} is about.
 *
 * <p>A client is named by its pipe today. Events carry this record rather than
 * a bare pipe name so that a longer-lived identity — the game account the
 * client is logged in to — can be added here as a new component later, without
 * changing the shape of any event record or of the code that reads
 * {@code event.client().pipe()}.</p>
 *
 * @param pipe the connection's pipe name, never {@code null}
 */
public record ClientRef(String pipe) {

    public ClientRef {
        Objects.requireNonNull(pipe, "pipe");
    }
}
