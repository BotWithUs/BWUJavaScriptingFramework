package com.botwithus.bot.core.resolver.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * SHA-1 digest helper. Used as the fallback checksum format for legacy
 * Maven Central artifacts that pre-date the SHA-256 sidecar rollout.
 *
 * <p>SHA-1 is cryptographically broken for collision resistance but
 * remains adequate for detecting accidental corruption in transit —
 * which is what the resolver is checking. Adversarial protection is
 * the job of the PGP signature (12.3).</p>
 */
public record Sha1Digest(byte[] sha1) {

    /** SHA-1 length in bytes. */
    public static final int LENGTH = 20;

    private static final String ALGORITHM = "SHA-1";
    private static final int HEX_CHARS_PER_BYTE = 2;
    private static final int HEX_LOWER_NIBBLE_MASK = 0x0F;
    private static final int HEX_HIGH_NIBBLE_SHIFT = 4;
    private static final int HEX_BYTE_MASK = 0xFF;
    private static final int IO_COPY_BUFFER_BYTES = 8192;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public Sha1Digest {
        Objects.requireNonNull(sha1, "sha1");
        if (sha1.length != LENGTH) {
            throw new IllegalArgumentException("SHA-1 digest must be " + LENGTH + " bytes, got " + sha1.length);
        }
        sha1 = sha1.clone();
    }

    @Override
    public byte[] sha1() {
        return sha1.clone();
    }

    public String toHex() {
        char[] out = new char[sha1.length * HEX_CHARS_PER_BYTE];
        for (int i = 0; i < sha1.length; i++) {
            int b = sha1[i] & HEX_BYTE_MASK;
            out[i * HEX_CHARS_PER_BYTE] = HEX_DIGITS[b >>> HEX_HIGH_NIBBLE_SHIFT];
            out[i * HEX_CHARS_PER_BYTE + 1] = HEX_DIGITS[b & HEX_LOWER_NIBBLE_MASK];
        }
        return new String(out);
    }

    public boolean matches(Sha1Digest other) {
        return other != null && MessageDigest.isEqual(sha1, other.sha1);
    }

    public static Optional<Sha1Digest> parseHex(String hex) {
        if (hex == null) {
            return Optional.empty();
        }
        String trimmed = hex.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            trimmed = trimmed.substring(0, space);
        }
        if (trimmed.length() != LENGTH * HEX_CHARS_PER_BYTE) {
            return Optional.empty();
        }
        byte[] out = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            int hi = Character.digit(trimmed.charAt(i * HEX_CHARS_PER_BYTE), 16);
            int lo = Character.digit(trimmed.charAt(i * HEX_CHARS_PER_BYTE + 1), 16);
            if (hi < 0 || lo < 0) {
                return Optional.empty();
            }
            out[i] = (byte) ((hi << HEX_HIGH_NIBBLE_SHIFT) | lo);
        }
        return Optional.of(new Sha1Digest(out));
    }

    public static Sha1Digest of(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[IO_COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return new Sha1Digest(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable on this JDK", e);
        }
    }

    // rule-exception: Object.equals contract forces the wide parameter type;
    // the record's auto-generated equals compares byte[] by reference, not
    // value, so we must override. Same rationale as ChecksumDigest.equals.
    @Override
    public boolean equals(Object o) {
        return o instanceof Sha1Digest other && Arrays.equals(sha1, other.sha1);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sha1);
    }

    @Override
    public String toString() {
        return "sha1:" + toHex();
    }
}
