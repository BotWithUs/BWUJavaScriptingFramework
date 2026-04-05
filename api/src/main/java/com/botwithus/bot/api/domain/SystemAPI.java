package com.botwithus.bot.api.domain;

import java.util.List;

/**
 * System-level operations: connectivity, event subscriptions, and client info.
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
     * Subscribes to a named game event so it is forwarded over the pipe.
     *
     * @param event the event name to subscribe to
     */
    void subscribe(String event);

    /**
     * Unsubscribes from a previously subscribed game event.
     *
     * @param event the event name to unsubscribe from
     */
    void unsubscribe(String event);

    /**
     * Returns the number of clients currently connected to the pipe server.
     *
     * @return the connected client count
     */
    int getClientCount();

    /**
     * Lists all available event names that can be subscribed to.
     *
     * @return a list of event names
     */
    List<String> listEvents();

    /**
     * Returns the calling client's current event subscriptions.
     *
     * @return a list of subscribed event names
     */
    List<String> getSubscriptions();
}
