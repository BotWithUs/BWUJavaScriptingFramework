package com.botwithus.bot.core.secrets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link CredentialStore} promises. A subclass supplies the store; the
 * cases write only under throwaway targets and delete every one afterwards, so
 * they are safe to run against the real operating-system store.
 */
abstract class CredentialStoreContract {

    /** The prefix every target a contract case writes starts with. */
    static final String TEST_TARGET_PREFIX = "BotWithUs/integrations/test-";

    private final List<String> written = new ArrayList<>();
    private CredentialStore store;

    /** A fresh store for one case. */
    abstract CredentialStore createStore();

    @BeforeEach
    void openStore() {
        store = createStore();
    }

    @AfterEach
    void deleteEverythingWritten() {
        for (String target : written) {
            store.delete(target);
            assertEquals(Optional.empty(), store.read(target), "left behind: " + target);
            confirmGone(target);
        }
    }

    /**
     * Called for each target after it was deleted, to check it is gone by some
     * other route than the store the case used. Nothing extra by default.
     */
    void confirmGone(String target) {
    }

    /** A target no other run uses, deleted after the case. */
    String throwawayTarget() {
        String target = TEST_TARGET_PREFIX + UUID.randomUUID();
        written.add(target);
        return target;
    }

    CredentialStore store() {
        return store;
    }

    @Test
    void read_unknownTarget_isEmpty() {
        assertEquals(Optional.empty(), store.read(throwawayTarget()));
    }

    @Test
    void write_thenRead_returnsTheSameSecret() {
        String target = throwawayTarget();
        store.write(target, new Secret("https://example.invalid/hook/abc"));

        assertEquals(Optional.of(new Secret("https://example.invalid/hook/abc")), store.read(target));
    }

    @Test
    void write_nonAsciiSecret_roundTrips() {
        String target = throwawayTarget();
        Secret secret = new Secret("tök_é✓_🔑");
        store.write(target, secret);

        assertEquals(Optional.of(secret), store.read(target));
    }

    @Test
    void write_twice_keepsTheSecondSecret() {
        String target = throwawayTarget();
        store.write(target, new Secret("first"));
        store.write(target, new Secret("second"));

        assertEquals(Optional.of(new Secret("second")), store.read(target));
    }

    @Test
    void write_longestAllowedSecret_roundTrips() {
        String target = throwawayTarget();
        Secret secret = new Secret("x".repeat(CredentialStore.MAX_SECRET_CHARS));
        store.write(target, secret);

        assertEquals(Optional.of(secret), store.read(target));
    }

    @Test
    void write_tooLongSecret_isRefusedAndStoresNothing() {
        String target = throwawayTarget();
        Secret secret = new Secret("x".repeat(CredentialStore.MAX_SECRET_CHARS + 1));

        assertThrows(IllegalArgumentException.class, () -> store.write(target, secret));
        assertEquals(Optional.empty(), store.read(target));
    }

    @Test
    void delete_existing_removesItAndReportsTrue() {
        String target = throwawayTarget();
        store.write(target, new Secret("gone soon"));

        assertTrue(store.delete(target));
        assertEquals(Optional.empty(), store.read(target));
    }

    @Test
    void delete_missing_reportsFalse() {
        assertFalse(store.delete(throwawayTarget()));
    }

    @Test
    void targets_areIndependent() {
        String first = throwawayTarget();
        String second = throwawayTarget();
        store.write(first, new Secret("one"));
        store.write(second, new Secret("two"));
        store.delete(first);

        assertEquals(Optional.of(new Secret("two")), store.read(second));
    }

    @Test
    void blankTarget_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> store.read(" "));
        assertThrows(IllegalArgumentException.class, () -> store.write("", new Secret("s")));
        assertThrows(IllegalArgumentException.class, () -> store.delete(""));
    }
}
