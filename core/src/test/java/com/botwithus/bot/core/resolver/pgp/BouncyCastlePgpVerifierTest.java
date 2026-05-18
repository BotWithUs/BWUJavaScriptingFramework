package com.botwithus.bot.core.resolver.pgp;

import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BouncyCastlePgpVerifierTest {

    @TempDir
    Path tempDir;

    private Path jarFile;
    private Path sigFile;
    private Path keyringFile;
    private BouncyCastlePgpVerifier verifier;
    private PGPSecretKeyRing secret;
    private String keyId;

    @BeforeEach
    void setUp() throws Exception {
        jarFile = tempDir.resolve("artifact.jar");
        sigFile = tempDir.resolve("artifact.jar.asc");
        keyringFile = tempDir.resolve("keyring.gpg");
        Files.write(jarFile, "this is the jar content under test".getBytes());

        verifier = new BouncyCastlePgpVerifier();
        secret = PgpTestFixture.generateKeyRing("Test User <test@example.invalid>");
        keyId = PgpTestFixture.writePublicKeyRing(secret, keyringFile);
    }

    @Test
    void verifiedWhenSignatureMatchesTrustedKey() throws Exception {
        PgpTestFixture.signDetached(secret, jarFile, sigFile);
        KeyRing keyRing = new KeyRing(keyringFile, Set.of(keyId));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        SignatureResult.Verified verified = assertInstanceOf(SignatureResult.Verified.class, result);
        assertEquals(keyId, verified.keyId());
    }

    @Test
    void unknownKeyWhenSignerNotInTrustedSet() throws Exception {
        PgpTestFixture.signDetached(secret, jarFile, sigFile);
        // Key is in the keyring file but not in trustedKeyIds — verifier
        // must refuse even though the signature itself would crypto-verify.
        KeyRing keyRing = new KeyRing(keyringFile, Set.of("0000000000000000"));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        SignatureResult.UnknownKey uk = assertInstanceOf(SignatureResult.UnknownKey.class, result);
        assertEquals(keyId, uk.keyId());
    }

    @Test
    void invalidSignatureWhenJarTampered() throws Exception {
        PgpTestFixture.signDetached(secret, jarFile, sigFile);
        // Tamper the jar AFTER signing. Same signature, different bytes.
        Files.write(jarFile, "tampered content".getBytes());
        KeyRing keyRing = new KeyRing(keyringFile, Set.of(keyId));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        assertInstanceOf(SignatureResult.InvalidSignature.class, result);
    }

    @Test
    void invalidSignatureWhenAscIsGarbage() throws IOException {
        PgpTestFixture.writeGarbageSignature(sigFile);
        KeyRing keyRing = new KeyRing(keyringFile, Set.of(keyId));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        assertInstanceOf(SignatureResult.InvalidSignature.class, result);
    }

    @Test
    void missingSignatureFileReturnsMissingSignatureFile() {
        // Don't create sigFile at all.
        KeyRing keyRing = new KeyRing(keyringFile, Set.of(keyId));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        assertInstanceOf(SignatureResult.MissingSignatureFile.class, result);
    }

    @Test
    void invalidSignatureWhenKeyringFileMissing() throws Exception {
        PgpTestFixture.signDetached(secret, jarFile, sigFile);
        Path nonexistentKeyring = tempDir.resolve("does-not-exist.gpg");
        KeyRing keyRing = new KeyRing(nonexistentKeyring, Set.of(keyId));

        SignatureResult result = verifier.verify(jarFile, sigFile, keyRing);
        assertInstanceOf(SignatureResult.InvalidSignature.class, result);
    }
}
