package com.botwithus.bot.core.resolver.pgp;

import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeyRingStoreTest {

    @TempDir
    Path tempDir;

    private Path keyringFile;
    private Path metadataFile;
    private KeyRingStore store;

    @BeforeEach
    void setUp() {
        keyringFile = tempDir.resolve("trusted-keys.gpg");
        metadataFile = tempDir.resolve("trusted-keys.json");
        store = new KeyRingStore(keyringFile, metadataFile);
    }

    @Test
    void emptyLoadFromMissingFile() throws IOException {
        store.load();
        assertTrue(store.list().isEmpty());
        assertTrue(store.trustedKeyIds().isEmpty());
        assertTrue(store.currentKeyRing().isEmpty());
    }

    @Test
    void addKeyImportsAndPersists() throws Exception {
        Path keyFile = tempDir.resolve("test-key.bin");
        PGPSecretKeyRing secret = PgpTestFixture.generateKeyRing("Test User <test@example.invalid>");
        String keyId = PgpTestFixture.writePublicKeyRing(secret, keyFile);

        List<String> imported = store.addKey(keyFile);
        assertEquals(1, imported.size());
        assertEquals(keyId, imported.get(0));

        assertEquals(1, store.list().size());
        assertEquals(keyId, store.list().get(0).keyId());
        assertTrue(store.trustedKeyIds().contains(keyId));

        // Reload from disk — the keyring and metadata both round-trip.
        KeyRingStore reloaded = new KeyRingStore(keyringFile, metadataFile);
        reloaded.load();
        assertEquals(List.of(keyId), reloaded.trustedKeyIds().stream().sorted().toList());

        Optional<KeyRing> current = reloaded.currentKeyRing();
        assertTrue(current.isPresent());
        assertEquals(keyringFile, current.get().keyringFile());
        assertEquals(Set.of(keyId), current.get().trustedKeyIds());
    }

    @Test
    void addKeyTwiceIsNoOpAfterFirstImport() throws Exception {
        Path keyFile = tempDir.resolve("test-key.bin");
        PGPSecretKeyRing secret = PgpTestFixture.generateKeyRing("Test User <test@example.invalid>");
        PgpTestFixture.writePublicKeyRing(secret, keyFile);

        store.addKey(keyFile);
        int sizeAfterFirst = store.list().size();
        store.addKey(keyFile);
        assertEquals(sizeAfterFirst, store.list().size(), "duplicate import must not double-count");
    }

    @Test
    void removeKeyDropsBothFromMetadataAndKeyring() throws Exception {
        Path keyFile = tempDir.resolve("test-key.bin");
        PGPSecretKeyRing secret = PgpTestFixture.generateKeyRing("Test User <test@example.invalid>");
        String keyId = PgpTestFixture.writePublicKeyRing(secret, keyFile);
        store.addKey(keyFile);

        assertTrue(store.removeKey(keyId));
        assertTrue(store.list().isEmpty());
        assertFalse(store.removeKey(keyId), "second remove must be a no-op");

        KeyRingStore reloaded = new KeyRingStore(keyringFile, metadataFile);
        reloaded.load();
        assertTrue(reloaded.list().isEmpty());
    }

    @Test
    void addKeyRejectsMissingFile() {
        Path notThere = tempDir.resolve("does-not-exist.asc");
        assertThrows(IOException.class, () -> store.addKey(notThere));
    }

    @Test
    void addKeyRejectsNonPgpFile() throws IOException {
        Path notAKey = tempDir.resolve("garbage.bin");
        Files.write(notAKey, "this is not a PGP key".getBytes());
        assertThrows(IOException.class, () -> store.addKey(notAKey));
    }
}
