package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.ClientLauncher;

import java.util.Optional;

/** The host's own {@link ClientLauncher}: what a scoping wrapper needs beyond the api. */
public interface HostClientLauncher extends ClientLauncher {

    /**
     * The account a client was launched on, as far as this host has seen it.
     * The service's {@code accountId} is the key a script's scope is matched on
     * (launcher ADR 0007, section 4.2).
     *
     * @param clientId a client id
     * @return its account id, when known
     */
    Optional<String> accountOf(String clientId);
}
