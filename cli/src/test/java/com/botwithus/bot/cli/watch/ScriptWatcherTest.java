package com.botwithus.bot.cli.watch;

import com.botwithus.bot.core.runtime.ScriptFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives a real watch over a temporary folder. */
class ScriptWatcherTest {

    private static final Duration SETTLE = Duration.ofMillis(50);
    /** Generous: file-system notification latency varies by machine and load. */
    private static final long EVENT_WAIT_SECONDS = 10;
    /** How long "nothing happened" is given to prove itself. */
    private static final long QUIET_WAIT_MS = 600;

    @TempDir
    Path scripts;

    private final BlockingQueue<Set<ScriptFolder>> changes = new LinkedBlockingQueue<>();
    private ScriptWatcher watcher;

    @AfterEach
    void stopWatcher() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    private void startWatching() {
        watcher = new ScriptWatcher(scripts, changes::add, SETTLE);
        assertTrue(watcher.start());
    }

    private Set<ScriptFolder> nextChange() throws InterruptedException {
        return changes.poll(EVENT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Lets stray follow-up events of an earlier change arrive and discards them. */
    private void drainUntilQuiet() throws InterruptedException {
        while (changes.poll(QUIET_WAIT_MS, TimeUnit.MILLISECONDS) != null) {
            changes.clear();
        }
    }

    private static Path writeJar(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), new byte[]{1});
    }

    @Test
    void aNewJar_reportsTheScriptsFolder() throws Exception {
        startWatching();

        writeJar(scripts, "woodcutter.jar");

        assertEquals(Set.of(ScriptFolder.SCRIPTS), nextChange());
    }

    @Test
    void aDeletedJar_reportsTheScriptsFolder() throws Exception {
        Path jar = writeJar(scripts, "woodcutter.jar");
        startWatching();

        Files.delete(jar);

        assertEquals(Set.of(ScriptFolder.SCRIPTS), nextChange());
    }

    @Test
    void aJarInTheManagementFolder_reportsTheManagementFolder() throws Exception {
        Path management = Files.createDirectory(scripts.resolve("management"));
        startWatching();

        writeJar(management, "rotator.jar");

        assertEquals(Set.of(ScriptFolder.MANAGEMENT), nextChange());
    }

    @Test
    void aJarDeletedFromTheManagementFolder_reportsTheManagementFolder() throws Exception {
        Path management = Files.createDirectory(scripts.resolve("management"));
        Path jar = writeJar(management, "rotator.jar");
        startWatching();

        Files.delete(jar);

        assertEquals(Set.of(ScriptFolder.MANAGEMENT), nextChange());
    }

    @Test
    void aManagementFolderCreatedLater_isWatchedFromThen() throws Exception {
        startWatching();
        Path management = Files.createDirectory(scripts.resolve("management"));
        assertEquals(Set.of(ScriptFolder.MANAGEMENT), nextChange(), "the folder appearing is a change");
        drainUntilQuiet();

        writeJar(management, "rotator.jar");

        assertEquals(Set.of(ScriptFolder.MANAGEMENT), nextChange());
    }

    @Test
    void aFileThatIsNotAJar_isIgnored() throws Exception {
        startWatching();

        Files.writeString(scripts.resolve("notes.txt"), "x");

        assertNull(changes.poll(QUIET_WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void afterStop_changesAreNotReported() throws Exception {
        startWatching();
        watcher.stop();

        writeJar(scripts, "woodcutter.jar");

        assertFalse(watcher.isRunning());
        assertNull(changes.poll(QUIET_WAIT_MS, TimeUnit.MILLISECONDS));
    }
}
