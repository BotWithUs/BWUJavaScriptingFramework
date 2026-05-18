package com.botwithus.bot.core.resolver.pgp;

/**
 * Outcome of one PGP detached-signature verification.
 */
public sealed interface SignatureResult
        permits SignatureResult.Verified,
                SignatureResult.InvalidSignature,
                SignatureResult.UnknownKey,
                SignatureResult.MissingSignatureFile {

    /** Signature verifies and the signing key is in the trusted set. */
    record Verified(String keyId) implements SignatureResult {}

    /** The signature is present and parses, but the cryptographic check failed. */
    record InvalidSignature(String reason) implements SignatureResult {}

    /** The signature parses but was made by a key not in the trusted keyring. */
    record UnknownKey(String keyId) implements SignatureResult {}

    /**
     * The repository did not publish a {@code .asc} for this artifact, and
     * the policy requires one.
     */
    record MissingSignatureFile() implements SignatureResult {

        public static final MissingSignatureFile INSTANCE = new MissingSignatureFile();
    }
}
