package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.Objects;

/**
 * A client the Start-on dialog can offer: one with a connection, live or
 * dropped, or one the host remembers from before whose game is closed.
 *
 * @param clientId    the connection name, or the client's key for a remembered client with no connection
 * @param name        the account playing there, or the connection name while it is unknown
 * @param isConnected whether its pipe is up now
 * @param offlineNote why it is not connected, such as {@code "reconnecting"}; ignored while connected
 * @param key         the key the host knows the client by; only a remembered account's key
 *                    can have a start wait for the client to come back
 */
public record ClientChoice(String clientId, String name, boolean isConnected, String offlineNote, ClientKey key) {

    public ClientChoice {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(offlineNote, "offlineNote");
        Objects.requireNonNull(key, "key");
    }
}
