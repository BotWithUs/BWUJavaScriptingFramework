package com.botwithus.bot.cli.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesFileStorageTest {

    /** Long enough that a save starts while the reader still holds the file, short of the retry budget. */
    private static final long READER_HOLD_MS = 60L;
    private static final long WAIT_S = 5L;

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(HostSettings.FILE_NAME);
    }

    private List<Path> filesInDir() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    @Test
    void save_thenLoad_roundTripsEveryEntry() throws IOException {
        PropertiesFileStorage storage = new PropertiesFileStorage(file());

        storage.save(Map.of("autoConnect", "false", "note", "déjà vu"));

        assertEquals(Map.of("autoConnect", "false", "note", "déjà vu"), storage.load());
        assertEquals(List.of(file()), filesInDir());
    }

    /**
     * Another process reading the settings file holds a handle on it for a
     * moment, and Windows refuses to rename over an open file. A save that lands
     * in that moment must wait it out rather than fail.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void save_whileAReaderBrieflyHoldsTheFile_succeedsOnceItLetsGo() throws Exception {
        PropertiesFileStorage storage = new PropertiesFileStorage(file());
        storage.save(Map.of("autoConnect", "true"));
        CountDownLatch opened = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().start(() -> holdOpen(opened));
        assertTrue(opened.await(WAIT_S, TimeUnit.SECONDS));

        storage.save(Map.of("autoConnect", "false"));

        reader.join();
        assertEquals(Map.of("autoConnect", "false"), storage.load());
        assertEquals(List.of(file()), filesInDir());
    }

    /** A save that cannot be made leaves the old file whole and nothing beside it. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void save_whileAReaderHoldsTheFileThroughout_failsAndLeavesNoTemporaryFile() throws IOException {
        PropertiesFileStorage storage = new PropertiesFileStorage(file());
        storage.save(Map.of("autoConnect", "true"));

        try (InputStream held = Files.newInputStream(file())) {
            assertThrows(IOException.class, () -> storage.save(Map.of("autoConnect", "false")));
            assertTrue(held.read() >= 0, "the reader still sees the old file");
        }

        assertEquals(Map.of("autoConnect", "true"), storage.load());
        assertEquals(List.of(file()), filesInDir());
    }

    private void holdOpen(CountDownLatch opened) {
        try (InputStream held = Files.newInputStream(file())) {
            held.read();
            opened.countDown();
            Thread.sleep(READER_HOLD_MS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
