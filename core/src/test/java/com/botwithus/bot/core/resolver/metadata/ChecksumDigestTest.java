package com.botwithus.bot.core.resolver.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumDigestTest {

    @TempDir
    Path tempDir;

    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void hexOfEmptyBytes_isKnownConstant() {
        ChecksumDigest empty = ChecksumDigest.of(new byte[0]);
        assertEquals(EMPTY_SHA256, empty.toHex());
    }

    @Test
    void parseHex_roundTrips() {
        Optional<ChecksumDigest> parsed = ChecksumDigest.parseHex(EMPTY_SHA256);
        assertTrue(parsed.isPresent());
        assertEquals(EMPTY_SHA256, parsed.get().toHex());
    }

    @Test
    void parseHex_toleratesTrailingFilename() {
        String line = EMPTY_SHA256 + "  artifact-1.0.jar";
        Optional<ChecksumDigest> parsed = ChecksumDigest.parseHex(line);
        assertTrue(parsed.isPresent());
        assertEquals(EMPTY_SHA256, parsed.get().toHex());
    }

    @Test
    void parseHex_rejectsBadLength() {
        assertTrue(ChecksumDigest.parseHex("abc123").isEmpty());
        assertTrue(ChecksumDigest.parseHex(null).isEmpty());
    }

    @Test
    void parseHex_rejectsNonHex() {
        String bad = "zz" + EMPTY_SHA256.substring(2);
        assertTrue(ChecksumDigest.parseHex(bad).isEmpty());
    }

    @Test
    void matches_isConstantTime() {
        ChecksumDigest a = ChecksumDigest.of(new byte[0]);
        ChecksumDigest b = ChecksumDigest.of(new byte[]{1});
        assertFalse(a.matches(b));
        assertTrue(a.matches(ChecksumDigest.of(new byte[0])));
    }

    @Test
    void digestOfFile_matchesDigestOfBytes() throws IOException {
        Path file = tempDir.resolve("data.bin");
        byte[] content = "hello, world".getBytes();
        Files.write(file, content);
        assertEquals(ChecksumDigest.of(content), ChecksumDigest.of(file));
    }

    @Test
    void recordIsDefensivelyCopied() {
        byte[] src = new byte[ChecksumDigest.LENGTH];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        ChecksumDigest d = new ChecksumDigest(src);
        src[0] = (byte) 0xFF;
        assertNotEquals((byte) 0xFF, d.sha256()[0]);
    }
}
