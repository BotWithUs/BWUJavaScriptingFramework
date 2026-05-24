package com.botwithus.bot.core.resolver.pgp;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * A user's set of trusted PGP key IDs and the on-disk file containing the
 * exported public-key block.
 *
 * <p>The file is the source of truth — {@link #trustedKeyIds} is the
 * subset of keys the user has explicitly trusted via
 * {@code scripts trust add}. A key present in the file but absent from
 * {@link #trustedKeyIds} is rejected as untrusted.</p>
 */
public record KeyRing(Path keyringFile, Set<String> trustedKeyIds) {

    public KeyRing {
        Objects.requireNonNull(keyringFile, "keyringFile");
        Objects.requireNonNull(trustedKeyIds, "trustedKeyIds");
        trustedKeyIds = Set.copyOf(trustedKeyIds);
    }
}
