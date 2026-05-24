package com.botwithus.bot.core.resolver;

import java.util.Objects;

/**
 * Repository basic-auth credentials. Stored in
 * {@code ~/.botwithus/credentials.json} keyed by repository id; the disk
 * file is locked down to the current user by {@code CredentialsStore}.
 */
public record Credentials(String username, String password) {

    public Credentials {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }
}
