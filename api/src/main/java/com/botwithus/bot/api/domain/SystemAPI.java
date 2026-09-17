package com.botwithus.bot.api.domain;

import java.util.List;

/**
 * System-level operations: connectivity and client info.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface SystemAPI {

    /**
     * Tests connectivity to the game client.
     *
     * @return {@code true} if the client responded successfully
     */
    boolean ping();

    /**
     * Lists all RPC method names supported by the game client.
     *
     * @return a list of available method names
     */
    List<String> listMethods();

    /**
     * Returns the number of clients currently connected to the pipe server.
     *
     * @return the connected client count
     */
    int getClientCount();
}
