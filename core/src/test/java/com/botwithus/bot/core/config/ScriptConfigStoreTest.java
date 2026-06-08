package com.botwithus.bot.core.config;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the per-account bucket layout introduced by plan humming-plotting-cerf:
 * each script's config lives under {@code <config-dir>/<accountUuid>/<scriptName>.json},
 * with two different uuids isolated from each other.
 *
 * <p>Tests override {@code user.home} for the duration of each test so the
 * store writes into a temp directory rather than the dev's real home.
 */
class ScriptConfigStoreTest {

    private static final String UUID_ALICE = "acc-alice";
    private static final String UUID_BOB = "acc-bob";

    @TempDir
    Path tempDir;

    private String savedHome;

    @BeforeEach
    void overrideHome() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
        }
    }

    private Path configRoot() {
        return tempDir.resolve(".botwithus").resolve("config");
    }

    private List<ConfigField> fields() {
        return List.of(
                ConfigField.intField("delay", "Delay", 600),
                ConfigField.stringField("target", "Target", "tree")
        );
    }

    @Test
    void load_returnsDefaults_whenSubdirDoesNotExist() {
        ScriptConfig cfg = ScriptConfigStore.load("Woodcutter", UUID_ALICE, fields());
        assertEquals(600, cfg.getInt("delay", -1));
        assertEquals("tree", cfg.getString("target", ""));
    }

    @Test
    void save_thenLoad_roundTripsValues() {
        ScriptConfigStore.save("Woodcutter", UUID_ALICE,
                new ScriptConfig(java.util.Map.of("delay", "1200", "target", "oak")));

        ScriptConfig loaded = ScriptConfigStore.load("Woodcutter", UUID_ALICE, fields());
        assertEquals(1200, loaded.getInt("delay", -1));
        assertEquals("oak", loaded.getString("target", ""));
    }

    @Test
    void save_writesUnderPerAccountSubdirectory() {
        ScriptConfigStore.save("Woodcutter", UUID_ALICE,
                new ScriptConfig(java.util.Map.of("delay", "1000")));

        Path expected = configRoot().resolve(UUID_ALICE).resolve("Woodcutter.json");
        assertTrue(Files.exists(expected), "config must land at " + expected);
    }

    @Test
    void twoUuidsAreIsolated() {
        ScriptConfigStore.save("Woodcutter", UUID_ALICE,
                new ScriptConfig(java.util.Map.of("target", "oak")));
        ScriptConfigStore.save("Woodcutter", UUID_BOB,
                new ScriptConfig(java.util.Map.of("target", "maple")));

        assertEquals("oak", ScriptConfigStore.load("Woodcutter", UUID_ALICE, fields())
                .getString("target", ""));
        assertEquals("maple", ScriptConfigStore.load("Woodcutter", UUID_BOB, fields())
                .getString("target", ""));
    }

    @Test
    void load_mergesPersistedOverDefaults_keepingMissingKeysAtDefault() {
        ScriptConfigStore.save("Woodcutter", UUID_ALICE,
                new ScriptConfig(java.util.Map.of("delay", "900")));

        ScriptConfig loaded = ScriptConfigStore.load("Woodcutter", UUID_ALICE, fields());
        assertEquals(900, loaded.getInt("delay", -1));
        assertEquals("tree", loaded.getString("target", ""));
    }
}
