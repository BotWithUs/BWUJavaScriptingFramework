package com.botwithus.bot.core.resolver.pgp;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Iterator;

/**
 * Test helper for BouncyCastle PGP fixtures: generates RSA key pairs at
 * test time, signs files, exports public-key rings to disk. All
 * BC-specific knowledge is contained here so the assertion-side tests
 * can stay readable.
 *
 * <p>RSA-2048 keys are used (smallest acceptable size for unit tests
 * without sacrificing realism). Key generation runs in well under 1s on
 * modern hardware.</p>
 */
final class PgpTestFixture {

    private static final int RSA_KEY_BITS = 2048;
    private static final int CERTIFICATION_LEVEL = PGPSignature.POSITIVE_CERTIFICATION;
    private static final int DOCUMENT_SIG_TYPE = PGPSignature.BINARY_DOCUMENT;
    private static final char[] EMPTY_PASSPHRASE = new char[0];
    private static final int SIGN_BUFFER_BYTES = 8192;
    private static final int KEY_ID_HEX_DIGITS = 16;
    private static final int KEY_AGE_DAYS = 2;
    private static final int KEY_VALID_DAYS = 1;

    private PgpTestFixture() {}

    /** Generates an in-memory PGP key pair and returns the secret-key ring. */
    static PGPSecretKeyRing generateKeyRing(String userId) throws Exception {
        return generate(userId, new Date(), 0L);
    }

    /**
     * Generates a key created {@value #KEY_AGE_DAYS} days ago with a
     * {@value #KEY_VALID_DAYS}-day validity — i.e. already expired. Used to
     * exercise the H2 expiry rejection in {@link BouncyCastlePgpVerifier}.
     */
    static PGPSecretKeyRing generateExpiredKeyRing(String userId) throws Exception {
        Date created = Date.from(Instant.now().minus(Duration.ofDays(KEY_AGE_DAYS)));
        return generate(userId, created, Duration.ofDays(KEY_VALID_DAYS).toSeconds());
    }

    private static PGPSecretKeyRing generate(String userId, Date created, long validSeconds) throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(RSA_KEY_BITS);
        KeyPair pair = rsa.generateKeyPair();

        PGPKeyPair pgpPair = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, pair, created);

        PGPSignatureSubpacketGenerator subpackets = new PGPSignatureSubpacketGenerator();
        subpackets.setKeyFlags(false, KeyFlags.SIGN_DATA);
        subpackets.setPreferredHashAlgorithms(false, new int[]{HashAlgorithmTags.SHA256});
        subpackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);
        if (validSeconds > 0) {
            subpackets.setKeyExpirationTime(false, validSeconds);
        }

        BcPGPDigestCalculatorProvider digestProvider = new BcPGPDigestCalculatorProvider();
        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(
                CERTIFICATION_LEVEL,
                pgpPair,
                userId,
                digestProvider.get(HashAlgorithmTags.SHA1),
                subpackets.generate(),
                null,
                new BcPGPContentSignerBuilder(pgpPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.NULL,
                        digestProvider.get(HashAlgorithmTags.SHA1))
                        .build(EMPTY_PASSPHRASE));

        return gen.generateSecretKeyRing();
    }

    /**
     * Writes the public half of {@code ring} to {@code dest} as a binary
     * keyring. Returns the master key's hex-uppercase key ID.
     */
    static String writePublicKeyRing(PGPSecretKeyRing ring, Path dest) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<PGPPublicKey> publicKeys = ring.getPublicKeys();
        while (publicKeys.hasNext()) {
            out.write(publicKeys.next().getEncoded());
        }
        Files.write(dest, out.toByteArray());
        return formatKeyId(ring.getPublicKey().getKeyID());
    }

    /**
     * Signs {@code dataFile} with {@code ring}'s master signing key and
     * writes an armored detached signature to {@code sigDest}.
     */
    static void signDetached(PGPSecretKeyRing ring, Path dataFile, Path sigDest) throws Exception {
        PGPSecretKey master = ring.getSecretKey();
        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(master.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
        sigGen.init(DOCUMENT_SIG_TYPE,
                master.extractPrivateKey(
                        new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
                                .build(EMPTY_PASSPHRASE)));

        try (InputStream in = new BufferedInputStream(Files.newInputStream(dataFile))) {
            byte[] buf = new byte[SIGN_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                sigGen.update(buf, 0, n);
            }
        }
        PGPSignature sig = sigGen.generate();
        ByteArrayOutputStream binaryOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(binaryOut)) {
            sig.encode(armored);
        }
        Files.write(sigDest, binaryOut.toByteArray());
    }

    /**
     * Writes the public half of {@code ring} to {@code dest} with a key-
     * revocation certificate attached to the master key, and returns its
     * hex-uppercase key ID. {@code PGPPublicKey.hasRevocation()} is true once
     * reloaded — exercises the H2 revocation rejection.
     */
    static String writeRevokedPublicKeyRing(PGPSecretKeyRing ring, Path dest) throws Exception {
        PGPSecretKey masterSecret = ring.getSecretKey();
        PGPPublicKey masterPublic = masterSecret.getPublicKey();
        PGPPrivateKey priv = masterSecret.extractPrivateKey(
                new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(EMPTY_PASSPHRASE));

        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(masterPublic.getAlgorithm(), HashAlgorithmTags.SHA256));
        sigGen.init(PGPSignature.KEY_REVOCATION, priv);
        PGPSignature revocation = sigGen.generateCertification(masterPublic);
        PGPPublicKey revoked = PGPPublicKey.addCertification(masterPublic, revocation);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(revoked.getEncoded());
        Iterator<PGPPublicKey> keys = ring.getPublicKeys();
        keys.next(); // master — replaced by the revoked copy above
        while (keys.hasNext()) {
            out.write(keys.next().getEncoded());
        }
        Files.write(dest, out.toByteArray());
        return formatKeyId(masterPublic.getKeyID());
    }

    /** Writes a single-byte garbage "signature" file — exercises the InvalidSignature path. */
    static void writeGarbageSignature(Path sigDest) throws IOException {
        Files.write(sigDest, new byte[]{'g', 'a', 'r', 'b', 'a', 'g', 'e'});
    }

    static String formatKeyId(long keyId) {
        return String.format("%0" + KEY_ID_HEX_DIGITS + "X", keyId);
    }
}
