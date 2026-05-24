package com.botwithus.bot.core.resolver.pgp;

import java.nio.file.Path;

/**
 * Verifies a detached PGP signature against a JAR and a keyring. The
 * production implementation lives in 12.3 ({@code BouncyCastlePgpVerifier});
 * 12.1 and 12.2 use {@link #ALWAYS_REJECT} so that any repository
 * configured with {@link PgpSignaturePolicy.Required} fails closed until
 * 12.3 lands.
 */
public interface PgpVerifier {

    SignatureResult verify(Path jar, Path detachedSignature, KeyRing keyRing);

    /**
     * Conservative default used before {@code BouncyCastlePgpVerifier}
     * ships. Always returns {@link SignatureResult.InvalidSignature} —
     * repositories with {@code requireSignature: true} cannot be used
     * until 12.3.
     */
    PgpVerifier ALWAYS_REJECT = (jar, sig, keyRing) ->
            new SignatureResult.InvalidSignature("PGP verifier not yet available (ships in 12.3)");
}
