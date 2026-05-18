package com.botwithus.bot.core.resolver.pgp;

/**
 * Per-repository policy for PGP signature verification. {@link Required}
 * carries the {@link KeyRing} of acceptable signers; {@link NotRequired}
 * means SHA-256 alone is enough (acceptable only behind HTTPS + auth).
 */
public sealed interface PgpSignaturePolicy permits PgpSignaturePolicy.Required, PgpSignaturePolicy.NotRequired {

    record Required(KeyRing keyRing) implements PgpSignaturePolicy {}

    record NotRequired() implements PgpSignaturePolicy {

        /** Shared instance — the record has no state. */
        public static final NotRequired INSTANCE = new NotRequired();
    }
}
