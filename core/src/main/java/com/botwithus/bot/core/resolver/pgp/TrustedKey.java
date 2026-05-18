package com.botwithus.bot.core.resolver.pgp;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for one trusted PGP key. Persisted to
 * {@code ~/.botwithus/trusted-keys.json} alongside the binary
 * {@code trusted-keys.gpg} keyring.
 *
 * <p>{@link #keyId} is the canonical 16-hex-digit uppercase OpenPGP
 * long-form key ID (same shape {@link BouncyCastlePgpVerifier} produces
 * from {@code PGPSignature.getKeyID()}). {@link #userId} is the human
 * "Name &lt;email&gt;" identity carried in the public-key packet; used
 * only for {@code scripts trust list} rendering. {@link #addedAt} is
 * when the user trusted the key — useful for audit.</p>
 */
public record TrustedKey(String keyId, String userId, Instant addedAt) {

    public TrustedKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(addedAt, "addedAt");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
    }
}
