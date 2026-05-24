package com.botwithus.bot.core.resolver.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Sha1DigestTest {

    @TempDir
    Path tempDir;

    private static final String EMPTY_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    @Test
    void hexOfEmptyBytesMatchesKnownConstant() throws IOException {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        assertEquals(EMPTY_SHA1, Sha1Digest.of(file).toHex());
    }

    @Test
    void parseHexRoundTrips() {
        Optional<Sha1Digest> parsed = Sha1Digest.parseHex(EMPTY_SHA1);
        assertTrue(parsed.isPresent());
        assertEquals(EMPTY_SHA1, parsed.get().toHex());
    }

    @Test
    void parseHexToleratesTrailingFilename() {
        Optional<Sha1Digest> parsed = Sha1Digest.parseHex(EMPTY_SHA1 + "  artifact.jar");
        assertTrue(parsed.isPresent());
        assertEquals(EMPTY_SHA1, parsed.get().toHex());
    }

    @Test
    void parseHexRejectsBadLength() {
        assertTrue(Sha1Digest.parseHex("abc123").isEmpty());
        assertTrue(Sha1Digest.parseHex(null).isEmpty());
    }
}
