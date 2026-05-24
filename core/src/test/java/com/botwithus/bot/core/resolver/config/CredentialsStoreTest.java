package com.botwithus.bot.core.resolver.config;

import com.botwithus.bot.core.resolver.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CredentialsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyLoadFromMissingFile() throws IOException {
        CredentialsStore store = new CredentialsStore(tempDir.resolve("credentials.json"));
        store.load();
        assertTrue(store.repoIds().isEmpty());
    }

    @Test
    void roundTripPersistsAndReloads() throws IOException {
        Path file = tempDir.resolve("credentials.json");
        CredentialsStore store = new CredentialsStore(file);
        store.put("nexus", new Credentials("alice", "s3cret"));
        store.put("central", new Credentials("bob", "t0ken"));
        store.save();

        CredentialsStore reloaded = new CredentialsStore(file);
        reloaded.load();
        assertEquals(Set.of("nexus", "central"), reloaded.repoIds());

        Optional<Credentials> nx = reloaded.lookup("nexus");
        assertTrue(nx.isPresent());
        assertEquals("alice", nx.get().username());
        assertEquals("s3cret", nx.get().password());
    }

    @Test
    void removeDeletesEntry() throws IOException {
        CredentialsStore store = new CredentialsStore(tempDir.resolve("credentials.json"));
        store.put("nexus", new Credentials("u", "p"));
        store.save();

        assertTrue(store.remove("nexus"));
        store.save();
        assertFalse(store.remove("nexus"));
        assertTrue(store.lookup("nexus").isEmpty());
    }

    @Test
    void malformedFileThrows() throws IOException {
        Path file = tempDir.resolve("credentials.json");
        Files.writeString(file, "{not json");
        CredentialsStore store = new CredentialsStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void posixPermissionsAreHardenedTo600() throws IOException {
        Path file = tempDir.resolve("credentials.json");
        CredentialsStore store = new CredentialsStore(file);
        store.put("nexus", new Credentials("u", "p"));
        store.save();

        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        assertNotNull(posix, "POSIX view must exist on Linux");
        Set<PosixFilePermission> perms = posix.readAttributes().permissions();
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "credentials.json must be 600 on POSIX");
    }
}
