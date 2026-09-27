package com.botwithus.bot.cli.gui.pages.installed;

import java.util.Objects;

/**
 * A client the Start-on dialog can offer.
 *
 * @param clientId    the connection name
 * @param name        the account playing there, or the connection name while it is unknown
 * @param isConnected whether its pipe is up now
 * @param offlineNote why it is not connected, such as {@code "reconnecting"}; ignored while connected
 */
public record ClientChoice(String clientId, String name, boolean isConnected, String offlineNote) {

    public ClientChoice {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(offlineNote, "offlineNote");
    }
}
