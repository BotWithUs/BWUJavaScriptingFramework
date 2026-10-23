package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ClientRef;

import java.util.List;
import java.util.Optional;

/**
 * What the alerts need to know about clients beyond the events themselves: the
 * name to show, whether a client's game has exited, and every client the host
 * knows. {@link LiveClientDirectory} reads the client registry and the connection
 * table; tests use a fixed one.
 */
public interface ClientDirectory {

    /** The name the client shows, if it is known. Never an account id: alerts leave the machine. */
    Optional<String> displayName(ClientRef client);

    /** Whether the client's game process has exited, so it cannot come back on this pipe. */
    boolean hasExited(ClientRef client);

    /** Every client the host knows now, live or remembered. */
    List<ClientSnapshot> clients();
}
