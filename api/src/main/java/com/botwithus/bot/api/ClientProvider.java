package com.botwithus.bot.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Provides access to all connected game clients.
 */
public interface ClientProvider {

    /**
     * Returns the client with the given name, if connected.
     *
     * @param name the client name to look up
     * @return an {@link Optional} containing the client, or empty if not found
     */
    Optional<Client> getClient(String name);

    /**
     * Returns all currently connected clients.
     *
     * @return an unmodifiable collection of connected clients
     */
    Collection<Client> getClients();

    /**
     * Returns the names of all currently connected clients.
     *
     * @return an unmodifiable set of client names
     */
    Set<String> getClientNames();
}
