package com.botwithus.bot.core.resolver.pgp;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;

/**
 * BouncyCastle-backed {@link PgpVerifier} for OpenPGP detached signatures.
 *
 * <p>Stateless — one instance shared across the resolver. BouncyCastle
 * classes are loaded lazily via class-init triggered by the first
 * {@link #verify} call; sessions with no {@code requireSignature: true}
 * repository never instantiate the BC class hierarchy.</p>
 *
 * <p>Verification rules:
 * <ul>
 *   <li>The detached signature must contain at least one
 *       {@link PGPSignature} that verifies cryptographically against the
 *       JAR's bytes using a key from the supplied {@link KeyRing}'s
 *       keyring file.</li>
 *   <li>That key's hex-uppercase key-ID must be listed in
 *       {@link KeyRing#trustedKeyIds()}.</li>
 *   <li>If neither condition holds for any signature in the file, the
 *       verifier returns the most-specific {@link SignatureResult}
 *       failure variant.</li>
 * </ul>
 *
 * <p>Failure precedence (most-to-least specific): {@code InvalidSignature}
 * &gt; {@code UnknownKey} &gt; {@code MissingSignatureFile}. The resolver
 * lifts these into {@link com.botwithus.bot.core.resolver.ResolveOutcome.SignatureInvalid}.</p>
 */
public final class BouncyCastlePgpVerifier implements PgpVerifier {

    private static final Logger log = LoggerFactory.getLogger(BouncyCastlePgpVerifier.class);
    private static final int VERIFY_BUFFER_BYTES = 8192;
    private static final int KEY_ID_HEX_DIGITS = 16;

    @Override
    public SignatureResult verify(Path jar, Path detachedSignature, KeyRing keyRing) {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(detachedSignature, "detachedSignature");
        Objects.requireNonNull(keyRing, "keyRing");

        if (!Files.exists(detachedSignature)) {
            return SignatureResult.MissingSignatureFile.INSTANCE;
        }
        if (!Files.exists(keyRing.keyringFile())) {
            return new SignatureResult.InvalidSignature(
                    "configured keyring file does not exist: " + keyRing.keyringFile());
        }

        PGPSignatureList signatures = readSignatures(detachedSignature);
        if (signatures == null) {
            return new SignatureResult.InvalidSignature(
                    "no PGP signature packets in " + detachedSignature);
        }

        PGPPublicKeyRingCollection keyRings;
        try {
            keyRings = loadKeyRings(keyRing.keyringFile());
        } catch (IOException | PGPException e) {
            return new SignatureResult.InvalidSignature(
                    "could not load keyring " + keyRing.keyringFile() + ": " + e.getMessage());
        }

        // Walk every signature in the detached file. The first one that
        // verifies against a key we hold gates the rest of the chain;
        // failures fall through to the next sig (PGP allows multi-sig).
        SignatureResult bestSoFar = SignatureResult.MissingSignatureFile.INSTANCE;
        for (int i = 0; i < signatures.size(); i++) {
            PGPSignature signature = signatures.get(i);
            String keyIdHex = formatKeyId(signature.getKeyID());

            PGPPublicKey publicKey = findKey(keyRings, signature.getKeyID());
            if (publicKey == null) {
                bestSoFar = preferBetter(bestSoFar, new SignatureResult.UnknownKey(keyIdHex));
                continue;
            }
            if (!keyRing.trustedKeyIds().contains(keyIdHex)) {
                bestSoFar = preferBetter(bestSoFar, new SignatureResult.UnknownKey(keyIdHex));
                continue;
            }

            SignatureResult one = verifyAgainstKey(signature, publicKey, jar, keyIdHex);
            if (isVerified(one)) {
                return one;
            }
            bestSoFar = preferBetter(bestSoFar, one);
        }
        return bestSoFar;
    }

    private static boolean isVerified(SignatureResult result) {
        return switch (result) {
            case SignatureResult.Verified v -> true;
            case SignatureResult.InvalidSignature inv -> false;
            case SignatureResult.UnknownKey uk -> false;
            case SignatureResult.MissingSignatureFile m -> false;
        };
    }

    /**
     * Reads the detached-signature file and returns the first
     * {@link PGPSignatureList} packet found, or {@code null} if none.
     * BouncyCastle's PGP packets can be wrapped in a
     * {@link PGPCompressedData} envelope — this method unwraps once.
     */
    private static PGPSignatureList readSignatures(Path detachedSignature) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(detachedSignature));
             InputStream decoded = PGPUtil.getDecoderStream(in)) {
            PGPObjectFactory factory = new BcPGPObjectFactory(decoded);
            Object obj = factory.nextObject();
            obj = switch (obj) {
                case PGPCompressedData compressed -> new BcPGPObjectFactory(compressed.getDataStream()).nextObject();
                case null -> null;
                default -> obj;
            };
            return switch (obj) {
                case PGPSignatureList list -> list;
                case null -> null;
                default -> null;
            };
        } catch (IOException | PGPException e) {
            log.debug("failed to parse signature file {}", detachedSignature, e);
            return null;
        }
    }

    private static PGPPublicKeyRingCollection loadKeyRings(Path keyringFile) throws IOException, PGPException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(keyringFile));
             InputStream decoded = PGPUtil.getDecoderStream(in)) {
            return new PGPPublicKeyRingCollection(decoded, new BcKeyFingerprintCalculator());
        }
    }

    private static PGPPublicKey findKey(PGPPublicKeyRingCollection keyRings, long keyId) {
        Iterator<PGPPublicKeyRing> rings = keyRings.getKeyRings();
        while (rings.hasNext()) {
            PGPPublicKeyRing ring = rings.next();
            PGPPublicKey key = ring.getPublicKey(keyId);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static SignatureResult verifyAgainstKey(PGPSignature signature, PGPPublicKey publicKey,
                                                    Path jar, String keyIdHex) {
        try {
            signature.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
        } catch (PGPException e) {
            return new SignatureResult.InvalidSignature("could not init signature for key " + keyIdHex
                    + ": " + e.getMessage());
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(jar))) {
            byte[] buf = new byte[VERIFY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                signature.update(buf, 0, n);
            }
        } catch (IOException e) {
            return new SignatureResult.InvalidSignature("could not read jar " + jar + ": " + e.getMessage());
        }
        try {
            return signature.verify()
                    ? new SignatureResult.Verified(keyIdHex)
                    : new SignatureResult.InvalidSignature("signature does not verify for key " + keyIdHex);
        } catch (PGPException e) {
            return new SignatureResult.InvalidSignature("PGP verify failure: " + e.getMessage());
        }
    }

    /**
     * Prefers a more-specific failure when iterating multi-sig detached
     * files. Order (most to least specific): InvalidSignature, UnknownKey,
     * MissingSignatureFile.
     */
    private static SignatureResult preferBetter(SignatureResult accumulated, SignatureResult candidate) {
        return severity(candidate) > severity(accumulated) ? candidate : accumulated;
    }

    // Specificity ranks for preferBetter(): higher == more informative failure.
    private static final int RANK_MISSING_SIGNATURE = 1;
    private static final int RANK_UNKNOWN_KEY       = 2;
    private static final int RANK_INVALID_SIGNATURE = 3;
    private static final int RANK_VERIFIED          = 4;

    private static int severity(SignatureResult r) {
        return switch (r) {
            case SignatureResult.Verified v               -> RANK_VERIFIED;
            case SignatureResult.InvalidSignature inv     -> RANK_INVALID_SIGNATURE;
            case SignatureResult.UnknownKey uk            -> RANK_UNKNOWN_KEY;
            case SignatureResult.MissingSignatureFile m   -> RANK_MISSING_SIGNATURE;
        };
    }

    /** Formats a PGP key-ID as the canonical 16-hex-digit uppercase string. */
    private static String formatKeyId(long keyId) {
        return String.format("%0" + KEY_ID_HEX_DIGITS + "X", keyId);
    }
}
