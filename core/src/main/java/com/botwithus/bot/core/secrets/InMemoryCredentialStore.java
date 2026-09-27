package com.botwithus.bot.core.secrets;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link CredentialStore} that keeps secrets in memory for the life of the
 * object. Nothing is persisted.
 *
 * <p>For tests, and as the host's fallback where the operating system's store
 * cannot be opened: integrations still work for the session, and the user is
 * told the secrets will not survive a restart.</p>
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, Secret> secrets = new ConcurrentHashMap<>();

    /** An empty store. */
    public InMemoryCredentialStore() {
    }

    @Override
    public Optional<Secret> read(String target) {
        return Optional.ofNullable(secrets.get(CredentialStore.requireTarget(target)));
    }

    @Override
    public void write(String target, Secret secret) {
        secrets.put(CredentialStore.requireTarget(target), CredentialStore.requireStorable(secret));
    }

    @Override
    public boolean delete(String target) {
        return secrets.remove(CredentialStore.requireTarget(target)) != null;
    }
}
