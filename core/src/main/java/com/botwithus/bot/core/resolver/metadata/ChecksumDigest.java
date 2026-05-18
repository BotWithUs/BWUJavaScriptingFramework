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
 * SHA-256 digest helpers. Wraps a defensive copy of the digest bytes so
 * the record is effectively immutable.
 */
public record ChecksumDigest(byte[] sha256) {

    /** SHA-256 length in bytes. */
    public static final int LENGTH = 32;

    private static final String ALGORITHM = "SHA-256";
    private static final int HEX_CHARS_PER_BYTE = 2;
    private static final int HEX_LOWER_NIBBLE_MASK = 0x0F;
    private static final int HEX_HIGH_NIBBLE_SHIFT = 4;
    private static final int HEX_BYTE_MASK = 0xFF;
    private static final int IO_COPY_BUFFER_BYTES = 8192;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public ChecksumDigest {
        Objects.requireNonNull(sha256, "sha256");
        if (sha256.length != LENGTH) {
            throw new IllegalArgumentException("SHA-256 digest must be " + LENGTH + " bytes, got " + sha256.length);
        }
        sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    public String toHex() {
        char[] out = new char[sha256.length * HEX_CHARS_PER_BYTE];
        for (int i = 0; i < sha256.length; i++) {
            int b = sha256[i] & HEX_BYTE_MASK;
            out[i * HEX_CHARS_PER_BYTE] = HEX_DIGITS[b >>> HEX_HIGH_NIBBLE_SHIFT];
            out[i * HEX_CHARS_PER_BYTE + 1] = HEX_DIGITS[b & HEX_LOWER_NIBBLE_MASK];
        }
        return new String(out);
    }

    public boolean matches(byte[] other) {
        if (other == null || other.length != sha256.length) {
            return false;
        }
        return MessageDigest.isEqual(sha256, other);
    }

    public boolean matches(ChecksumDigest other) {
        return other != null && matches(other.sha256);
    }

    /**
     * Parses a hex-encoded digest. Tolerates leading whitespace and a
     * trailing filename column (Maven Central publishes {@code <hex>  <name>}).
     */
    public static Optional<ChecksumDigest> parseHex(String hex) {
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
        return Optional.of(new ChecksumDigest(out));
    }

    /** Computes the SHA-256 of a file's contents. */
    public static ChecksumDigest of(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[IO_COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return new ChecksumDigest(digest.digest());
    }

    public static ChecksumDigest of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        MessageDigest digest = newDigest();
        digest.update(bytes);
        return new ChecksumDigest(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JDK", e);
        }
    }

    // rule-exception: Object.equals contract forces the wide parameter type;
    // the record's auto-generated equals compares byte[] by reference, not
    // value, so we must override. See JBotWithUsV2/CLAUDE.md "Java rules
    // exceptions" for the documented pattern.
    @Override
    public boolean equals(Object o) {
        return o instanceof ChecksumDigest other && Arrays.equals(sha256, other.sha256);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sha256);
    }

    @Override
    public String toString() {
        return "sha256:" + toHex();
    }
}
