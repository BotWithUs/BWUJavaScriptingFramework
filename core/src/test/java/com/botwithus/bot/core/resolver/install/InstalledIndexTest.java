package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.MavenCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InstalledIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyLoadFromMissingFile() throws IOException {
        InstalledIndex idx = new InstalledIndex(tempDir.resolve(".installed.json"));
        idx.load();
        assertEquals(0, idx.size());
    }

    @Test
    void putThenSaveThenLoadRoundTrips() throws IOException {
        Path file = tempDir.resolve(".installed.json");
        InstalledIndex idx = new InstalledIndex(file);

        InstalledEntry entry = new InstalledEntry(
                "my-script-1.0.0.jar",
                MavenCoord.of("com.example", "my-script", "1.0.0"),
                Instant.parse("2026-05-17T10:00:00Z"),
                "deadbeef",
                "central");
        idx.put(entry);
        idx.save();

        assertTrue(Files.exists(file));

        InstalledIndex loaded = new InstalledIndex(file);
        loaded.load();
        assertEquals(1, loaded.size());

        Optional<InstalledEntry> found = loaded.find(MavenCoord.of("com.example", "my-script"));
        assertTrue(found.isPresent());
        assertEquals("1.0.0", found.get().version());
        assertEquals("central", found.get().repoId());
        assertEquals("deadbeef", found.get().sha256Hex());
    }

    @Test
    void removeDropsEntry() throws IOException {
        InstalledIndex idx = new InstalledIndex(tempDir.resolve(".installed.json"));
        idx.put(new InstalledEntry(
                "x.jar",
                MavenCoord.of("g", "a", "1.0"),
                Instant.now(),
                "deadbeef",
                "r"));
        idx.save();

        idx.remove(MavenCoord.of("g", "a"));
        idx.save();

        InstalledIndex loaded = new InstalledIndex(tempDir.resolve(".installed.json"));
        loaded.load();
        assertEquals(0, loaded.size());
    }

    @Test
    void malformedFileThrows() throws IOException {
        Path file = tempDir.resolve(".installed.json");
        Files.writeString(file, "{not json");
        InstalledIndex idx = new InstalledIndex(file);
        assertThrows(IOException.class, idx::load);
    }

    @Test
    void saveCreatesParentDirectory() throws IOException {
        Path file = tempDir.resolve("nested").resolve("deep").resolve(".installed.json");
        InstalledIndex idx = new InstalledIndex(file);
        idx.put(new InstalledEntry("a.jar",
                MavenCoord.of("g", "a", "1"),
                Instant.now(),
                "d",
                "r"));
        idx.save();
        assertTrue(Files.exists(file));
    }

    @Test
    void allReturnsCopy() throws IOException {
        InstalledIndex idx = new InstalledIndex(tempDir.resolve(".installed.json"));
        idx.put(new InstalledEntry("a.jar",
                MavenCoord.of("g", "a", "1"),
                Instant.now(),
                "d", "r"));
        List<InstalledEntry> snapshot = idx.all();
        assertEquals(1, snapshot.size());
        idx.remove(MavenCoord.of("g", "a"));
        assertEquals(1, snapshot.size(), "snapshot must be an immutable copy");
    }
}
