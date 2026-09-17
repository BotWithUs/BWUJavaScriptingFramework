package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdnRendezvousTest {

    private static final byte[] PAYLOAD = "courier payload".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void atomicWrite_thenAwaitFile_roundTripsTheBytes() throws Exception {
        Path target = dir.resolve("42.cat");

        SdnRendezvous.atomicWrite(target, PAYLOAD);

        assertArrayEquals(PAYLOAD, SdnRendezvous.awaitFile(target, Duration.ofMillis(200)));
    }

    @Test
    void atomicWrite_leavesNoTemporaryFileBehind() throws Exception {
        SdnRendezvous.atomicWrite(dir.resolve("42.cat"), PAYLOAD);

        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "a torn write would leave a .tmp sibling");
        }
    }

    @Test
    void atomicWrite_overExistingFile_replacesIt() throws Exception {
        Path target = dir.resolve("42.cat");
        SdnRendezvous.atomicWrite(target, "old".getBytes(StandardCharsets.UTF_8));

        SdnRendezvous.atomicWrite(target, PAYLOAD);

        assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
    }

    @Test
    void awaitFile_whenNothingArrives_returnsNullAfterTheTimeout() throws Exception {
        long started = System.nanoTime();

        byte[] answer = SdnRendezvous.awaitFile(dir.resolve("absent"), Duration.ofMillis(250));

        assertNull(answer);
        assertTrue(System.nanoTime() - started >= Duration.ofMillis(200).toNanos(),
                "it must actually wait rather than returning immediately");
    }

    @Test
    void awaitFile_whenTheCourierAnswersLate_stillReadsIt() throws Exception {
        Path target = dir.resolve("42.sdn");
        Thread courier = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(120);
                SdnRendezvous.atomicWrite(target, PAYLOAD);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        byte[] answer = SdnRendezvous.awaitFile(target, Duration.ofSeconds(5));
        courier.join();

        assertNotNull(answer, "a reply written while we were waiting must be picked up");
        assertArrayEquals(PAYLOAD, answer);
    }

    @Test
    void deleteQuietly_onMissingFile_doesNotThrow() {
        SdnRendezvous.deleteQuietly(dir.resolve("never-existed"));
    }

    @Test
    void deleteQuietly_removesTheFile() throws Exception {
        Path target = dir.resolve("42.pub");
        SdnRendezvous.atomicWrite(target, PAYLOAD);

        SdnRendezvous.deleteQuietly(target);

        assertFalse(Files.exists(target));
    }

    @Test
    void directory_honoursTheOverrideProperty() {
        String previous = System.getProperty(SdnRendezvous.DIR_PROP);
        System.setProperty(SdnRendezvous.DIR_PROP, dir.toString());
        try {
            assertEquals(dir, SdnRendezvous.directory());
        } finally {
            if (previous == null) {
                System.clearProperty(SdnRendezvous.DIR_PROP);
            } else {
                System.setProperty(SdnRendezvous.DIR_PROP, previous);
            }
        }
    }

    @Test
    void currentPid_isStableWithinAProcess() {
        assertEquals(SdnRendezvous.currentPid(), SdnRendezvous.currentPid());
    }
}
