package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviousLoaderTrackerTest {

    private static final String MARKER = "marker.txt";

    @TempDir
    Path tempDir;

    /**
     * Every loader a test opened. A pinned loader deliberately survives
     * {@code closeAll}, and on Windows that keeps a handle on the JAR — which
     * would then defeat {@code @TempDir}'s cleanup. Closing them here is the
     * test harness doing what production deliberately does not.
     */
    private final List<URLClassLoader> opened = new ArrayList<>();

    @AfterEach
    void closeLoaders() throws IOException {
        for (URLClassLoader loader : opened) {
            loader.close();
        }
    }

    /** A one-entry JAR, so a loader over it has something real to resolve. */
    private Path writeJar(String name) throws IOException {
        Path jar = tempDir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(MARKER));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private URLClassLoader loaderOver(String jarName) throws IOException {
        URLClassLoader loader = new URLClassLoader(new URL[]{writeJar(jarName).toUri().toURL()});
        opened.add(loader);
        return loader;
    }

    /**
     * A closed {@link URLClassLoader} can no longer resolve resources it
     * defines, so a null lookup for a name we know is in the JAR means closed.
     */
    private static boolean canStillRead(URLClassLoader loader) {
        return loader.findResource(MARKER) != null;
    }

    @Test
    @DisplayName("an unpinned loader is closed on reload")
    void closesUnpinnedLoaders() throws IOException {
        PreviousLoaderTracker tracker = new PreviousLoaderTracker();
        URLClassLoader loader = loaderOver("plain.jar");
        assertTrue(canStillRead(loader), "sanity: the loader resolves before closeAll");
        tracker.add(loader);

        tracker.closeAll();

        assertFalse(canStillRead(loader), "a loader with no live thread should be closed as before");
    }

    @Test
    @DisplayName("a pinned loader survives reload")
    void keepsPinnedLoadersOpen() throws IOException {
        // Deliberately leaking the loader is the lesser evil: closing it under a
        // live script thread gives that thread NoClassDefFoundError, and on
        // Windows the running loader holds the JAR handle open anyway, so the
        // close can't release it and every later reload wedges.
        PreviousLoaderTracker tracker = new PreviousLoaderTracker();
        URLClassLoader loader = loaderOver("pinned.jar");
        tracker.add(loader);
        tracker.pin(loader);

        tracker.closeAll();

        assertTrue(canStillRead(loader),
                "a loader whose script thread is still running must stay open");
    }

    @Test
    @DisplayName("pinning one loader doesn't spare the others")
    void pinIsPerLoader() throws IOException {
        PreviousLoaderTracker tracker = new PreviousLoaderTracker();
        URLClassLoader pinned = loaderOver("keep.jar");
        URLClassLoader ordinary = loaderOver("drop.jar");
        tracker.add(pinned);
        tracker.add(ordinary);
        tracker.pin(pinned);

        tracker.closeAll();

        assertTrue(canStillRead(pinned));
        assertFalse(canStillRead(ordinary));
    }

    @Test
    @DisplayName("pinning null is a no-op")
    void pinToleratesNull() {
        PreviousLoaderTracker tracker = new PreviousLoaderTracker();
        assertDoesNotThrow(() -> tracker.pin(null));
    }
}
