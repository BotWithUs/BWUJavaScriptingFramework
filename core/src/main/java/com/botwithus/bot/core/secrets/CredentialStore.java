package com.botwithus.bot.core.secrets;

import java.util.Objects;
import java.util.Optional;

/**
 * Where the host keeps secrets — webhook URLs, access tokens — so that they never
 * sit in {@code config.properties}, an export, or the repository.
 *
 * <p>A secret is stored under a <em>target</em>, a name such as
 * {@code BotWithUs/integrations/discord}. Targets are not secret; they show up in
 * the operating system's own credential UI. Every method is safe to call from any
 * thread, and blocks only as long as the backing store takes.</p>
 *
 * <p>Failures of the backing store surface as {@link CredentialStoreException}.
 * Neither a failure nor anything else a store logs ever includes the secret.</p>
 */
public interface CredentialStore {

    /**
     * The longest secret a store accepts, in UTF-16 code units. Windows caps a
     * credential blob at {@code CRED_MAX_CREDENTIAL_BLOB_SIZE} (5 × 512 bytes) and a
     * secret is stored as UTF-16, two bytes a unit; every store honours the same
     * cap so a test against one says something about the other.
     */
    int MAX_SECRET_CHARS = 1280;

    /** The longest target name a store accepts ({@code CRED_MAX_GENERIC_TARGET_NAME_LENGTH} − 1). */
    int MAX_TARGET_CHARS = 32766;

    /** The secret stored under {@code target}, or empty if there is none. */
    Optional<Secret> read(String target);

    /**
     * Stores {@code secret} under {@code target}, replacing whatever was there.
     *
     * @throws IllegalArgumentException if the secret is longer than {@link #MAX_SECRET_CHARS}
     */
    void write(String target, Secret secret);

    /**
     * Removes the secret stored under {@code target}.
     *
     * @return {@code true} if there was one to remove
     */
    boolean delete(String target);

    /**
     * Checks a target name, for implementations to call first.
     *
     * @throws IllegalArgumentException if it is blank or too long
     */
    static String requireTarget(String target) {
        Objects.requireNonNull(target, "target");
        if (target.isBlank() || target.length() > MAX_TARGET_CHARS) {
            throw new IllegalArgumentException("a credential target must be 1 to "
                    + MAX_TARGET_CHARS + " characters and not blank");
        }
        return target;
    }

    /**
     * Checks a secret's length, for implementations to call first.
     *
     * @throws IllegalArgumentException if it is longer than {@link #MAX_SECRET_CHARS}
     */
    static Secret requireStorable(Secret secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.reveal().length() > MAX_SECRET_CHARS) {
            throw new IllegalArgumentException("a secret can be at most " + MAX_SECRET_CHARS
                    + " characters, this one is " + secret.reveal().length());
        }
        return secret;
    }
}
