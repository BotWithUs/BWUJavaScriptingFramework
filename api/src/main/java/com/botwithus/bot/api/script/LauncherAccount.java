package com.botwithus.bot.api.script;

import java.util.Objects;

/**
 * An account the launcher can start a client on. No credential is ever part of it.
 *
 * @param id   the account's UUID: the same id a connected client reports as its
 *             account, and the one {@link ManagementTarget.ClientScript#accountUuid()} holds
 * @param name the account's display name
 */
public record LauncherAccount(String id, String name) {
    public LauncherAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
