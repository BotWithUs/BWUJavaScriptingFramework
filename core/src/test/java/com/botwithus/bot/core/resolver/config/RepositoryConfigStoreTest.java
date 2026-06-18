package com.botwithus.bot.core.resolver.config;

import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void firstLoadSeedsBundledCentral() throws IOException {
        RepositoryConfigStore store = new RepositoryConfigStore(tempDir.resolve("repositories.json"));
        store.load();
        Optional<Repository> central = store.find(RepositoryConfigStore.BUNDLED_CENTRAL_ID);
        assertTrue(central.isPresent());
        assertEquals(RepositoryConfigStore.BUNDLED_CENTRAL_URL.toString(), central.get().url().toString());
        assertEquals(MavenRepositoryDriver.TYPE_ID, central.get().driverId());
        assertFalse(central.get().snapshots());
        // PR-E 12.3: public repos default to PGP-required so unsigned
        // central artifacts fail closed until the user explicitly trusts
        // a signing key via `scripts trust add`.
        assertTrue(central.get().requireSignature());
        assertTrue(central.get().searchEndpoint().isPresent());
    }

    @Test
    void roundTripPreservesAllFields() throws IOException {
        Path file = tempDir.resolve("repositories.json");
        RepositoryConfigStore store = new RepositoryConfigStore(file);

        Repository nexus = new Repository(
                "internal",
                URI.create("https://nexus.example/repository/scripts/"),
                MavenRepositoryDriver.TYPE_ID,
                /* snapshots */ true,
                /* requireSignature */ true,
                Optional.of("internal-token"),
                Optional.of(URI.create("https://nexus.example/service/rest/v1/search")));
        store.put(nexus);
        store.save();

        RepositoryConfigStore reloaded = new RepositoryConfigStore(file);
        reloaded.load();
        Optional<Repository> got = reloaded.find("internal");
        assertTrue(got.isPresent());
        assertEquals(nexus.url(), got.get().url());
        assertEquals(nexus.driverId(), got.get().driverId());
        assertEquals(nexus.snapshots(), got.get().snapshots());
        assertEquals(nexus.requireSignature(), got.get().requireSignature());
        assertEquals(nexus.credentialsRef(), got.get().credentialsRef());
        assertEquals(nexus.searchEndpoint(), got.get().searchEndpoint());
    }

    @Test
    void removeDeletesEntry() throws IOException {
        RepositoryConfigStore store = new RepositoryConfigStore(tempDir.resolve("repositories.json"));
        store.load();
        assertTrue(store.remove(RepositoryConfigStore.BUNDLED_CENTRAL_ID));
        store.save();
        assertFalse(store.remove(RepositoryConfigStore.BUNDLED_CENTRAL_ID));
    }

    @Test
    void malformedFileThrows() throws IOException {
        Path file = tempDir.resolve("repositories.json");
        Files.writeString(file, "{not json");
        RepositoryConfigStore store = new RepositoryConfigStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void normalizesRepositoryUrlWithoutTrailingSlash() throws IOException {
        Path file = tempDir.resolve("repositories.json");
        RepositoryConfigStore store = new RepositoryConfigStore(file);
        Repository r = new Repository(
                "no-slash",
                URI.create("https://nexus.example/repository/scripts"),
                MavenRepositoryDriver.TYPE_ID,
                false, false,
                Optional.empty(), Optional.empty());
        store.put(r);
        store.save();

        RepositoryConfigStore reloaded = new RepositoryConfigStore(file);
        reloaded.load();
        assertEquals("https://nexus.example/repository/scripts/",
                reloaded.find("no-slash").orElseThrow().url().toString());
    }

    @Test
    void missingRequireSignatureFieldDefaultsToRequired() throws IOException {
        Path file = tempDir.resolve("repositories.json");
        // M1: a hand-edited / older entry that omits `requireSignature` must
        // fail closed — read as `true`, not the previous fail-open `false`.
        Files.writeString(file, """
                [{"id":"legacy","url":"https://example/repo/"}]
                """);
        RepositoryConfigStore store = new RepositoryConfigStore(file);
        store.load();
        Repository got = store.find("legacy").orElseThrow();
        assertTrue(got.requireSignature());
    }

    @Test
    void legacyTypeFieldIsTranslated() throws IOException {
        Path file = tempDir.resolve("repositories.json");
        // Pre-12.1b on-disk shape: an entry with `type: SNAPSHOT` instead of
        // `driverId` + `snapshots`. The adapter must translate so existing
        // user files still load.
        Files.writeString(file, """
                [{"id":"legacy","url":"https://example/repo/","type":"SNAPSHOT","requireSignature":false}]
                """);
        RepositoryConfigStore store = new RepositoryConfigStore(file);
        store.load();
        Repository got = store.find("legacy").orElseThrow();
        assertEquals(MavenRepositoryDriver.TYPE_ID, got.driverId());
        assertTrue(got.snapshots());
    }
}
