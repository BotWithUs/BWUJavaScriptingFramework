package com.botwithus.bot.core.config;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real store over real files in a temporary config folder; saves run in line. */
class ManagementSettingsStoreTest {

    private static final String BREAKS = "Break Scheduler";
    private static final String CLIENT_TARGET = "client-3f9a1c2e-58b0-4d7a-9e21-6c0f4b7d2a18-Walk to Flag";
    private static final String GROUP_TARGET = "group-0b4f2a36-5d1e-4c8a-9f70-2e6b1d3c4a58";
    private static final String LEGACY_JSON = "{\"breakEvery\":\"60\",\"logOut\":\"false\"}";

    @TempDir
    Path configDir;

    private ManagementSettingsStore store() {
        return new ManagementSettingsStore(configDir, Runnable::run);
    }

    private static List<ConfigField> fields() {
        return List.of(
                ConfigField.intField("breakEvery", "Break every (min)", 90),
                ConfigField.intField("breakLength", "Break length (min)", 15),
                ConfigField.boolField("logOut", "Log out during breaks", true));
    }

    private Path bucket() {
        return configDir.resolve(ManagementSettingsStore.BUCKET);
    }

    /** Where hosts before per-target settings kept a management script's one config. */
    private Path legacyFile(String script) {
        return bucket().resolve(ScriptConfigStore.safeName(script) + ".json");
    }

    private void writeLegacy(String script, String json) throws IOException {
        Files.createDirectories(bucket());
        Files.writeString(legacyFile(script), json, StandardCharsets.UTF_8);
    }

    @Nested
    class Defaults {

        @Test
        void aScriptNeverSaved_getsItsDeclaredDefaults() {
            ScriptConfig defaults = store().defaults(BREAKS, fields());

            assertEquals(Map.of("breakEvery", "90", "breakLength", "15", "logOut", "true"), defaults.asMap());
        }

        @Test
        void savedDefaults_overlayTheDeclaredOnes_andSurviveARestart() {
            store().saveDefaults(BREAKS, Map.of("breakEvery", "45"));

            ScriptConfig reloaded = store().defaults(BREAKS, fields());

            assertAll(
                    () -> assertEquals("45", reloaded.asMap().get("breakEvery")),
                    () -> assertEquals("15", reloaded.asMap().get("breakLength")),
                    () -> assertTrue(Files.isRegularFile(store().scriptDir(BREAKS)
                            .resolve(ManagementSettingsStore.DEFAULTS_FILE))));
        }
    }

    @Nested
    class Overrides {

        @Test
        void aTargetsOwnValues_areKeptInTheirOwnFile_andReadBackAfterARestart() {
            store().saveOverrides(BREAKS, CLIENT_TARGET, Map.of("breakLength", "5"));
            store().saveOverrides(BREAKS, GROUP_TARGET, Map.of("breakEvery", "120"));

            ManagementSettingsStore reloaded = store();

            assertAll(
                    () -> assertEquals(Map.of("breakLength", "5"), reloaded.overrides(BREAKS, CLIENT_TARGET)),
                    () -> assertEquals(Map.of("breakEvery", "120"), reloaded.overrides(BREAKS, GROUP_TARGET)),
                    () -> assertEquals(Map.of(), reloaded.overrides(BREAKS, "group-nobody")));
        }

        @Test
        void noOwnValuesLeft_deletesTheTargetsFile() {
            ManagementSettingsStore store = store();
            store.saveOverrides(BREAKS, CLIENT_TARGET, Map.of("breakLength", "5"));
            Path file = store.overridesFile(BREAKS, CLIENT_TARGET);
            assertTrue(Files.isRegularFile(file));

            store.saveOverrides(BREAKS, CLIENT_TARGET, Map.of());

            assertAll(
                    () -> assertFalse(Files.exists(file)),
                    () -> assertEquals(Map.of(), store().overrides(BREAKS, CLIENT_TARGET)));
        }

        @Test
        void aTargetCannotBeCalledDefaults_orItWouldOverwriteThem() {
            assertThrows(IllegalArgumentException.class,
                    () -> store().saveOverrides(BREAKS, ManagementSettingsStore.DEFAULTS_NAME, Map.of("a", "b")));
        }

        @Test
        void anUnreadableFile_readsAsEmpty_andIsSetAsideRatherThanLost() throws IOException {
            Path file = store().overridesFile(BREAKS, CLIENT_TARGET);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{not json", StandardCharsets.UTF_8);

            Map<String, String> read = store().overrides(BREAKS, CLIENT_TARGET);

            assertAll(
                    () -> assertEquals(Map.of(), read),
                    () -> assertEquals("{not json", Files.readString(
                            file.resolveSibling(file.getFileName() + ManagementSettingsStore.UNREADABLE_SUFFIX))));
        }
    }

    @Nested
    class FileNames {

        @Test
        void targetKeysWithSpacesAndPunctuation_becomeSafeDistinctFileNames() {
            String spaced = ManagementSettingsStore.fileNameOf("client-abc-Walk to Flag");
            String underscored = ManagementSettingsStore.fileNameOf("client-abc-Walk_to_Flag");
            String dotted = ManagementSettingsStore.fileNameOf("client-abc-v1.2/..\\x:y*?");

            assertAll(
                    () -> assertTrue(spaced.matches("[A-Za-z0-9_%-]+"), spaced),
                    () -> assertTrue(dotted.matches("[A-Za-z0-9_%-]+"), dotted),
                    () -> assertNotEquals(spaced, underscored));
        }

        @Test
        void aWindowsDeviceName_isNotUsedAsIs() {
            String con = ManagementSettingsStore.fileNameOf("CON");
            String lpt = ManagementSettingsStore.fileNameOf("lpt1");

            assertAll(
                    () -> assertNotEquals("CON", con),
                    () -> assertNotEquals("lpt1", lpt),
                    () -> assertTrue(con.matches("[A-Za-z0-9_%-]+"), con));
        }

        @Test
        void twoScriptsTheOldSanitiserMerged_getTheirOwnFolders() {
            ManagementSettingsStore store = store();

            assertNotEquals(store.scriptDir("Break Scheduler"), store.scriptDir("Break_Scheduler"));
        }
    }

    @Nested
    class Migration {

        @Test
        void theFlatConfigBecomesTheDefaults_andIsKeptAsABackup() throws IOException {
            writeLegacy(BREAKS, LEGACY_JSON);

            ScriptConfig defaults = store().defaults(BREAKS, fields());

            Path backup = legacyFile(BREAKS).resolveSibling(legacyFile(BREAKS).getFileName()
                    + ManagementSettingsStore.BACKUP_SUFFIX);
            assertAll(
                    () -> assertEquals("60", defaults.asMap().get("breakEvery")),
                    () -> assertEquals("false", defaults.asMap().get("logOut")),
                    () -> assertEquals("15", defaults.asMap().get("breakLength")),
                    () -> assertFalse(Files.exists(legacyFile(BREAKS)), "moved away"),
                    () -> assertEquals(LEGACY_JSON, Files.readString(backup)));
        }

        @Test
        void migratingAgain_changesNothing_andNeverRollsTheDefaultsBack() throws IOException {
            writeLegacy(BREAKS, LEGACY_JSON);
            store().defaults(BREAKS, fields());
            store().saveDefaults(BREAKS, Map.of("breakEvery", "30"));
            List<Path> before = listing();

            ScriptConfig afterRestart = store().defaults(BREAKS, fields());
            ScriptConfig afterAnother = store().defaults(BREAKS, fields());

            assertAll(
                    () -> assertEquals("30", afterRestart.asMap().get("breakEvery")),
                    () -> assertEquals("30", afterAnother.asMap().get("breakEvery")),
                    () -> assertEquals(before, listing()));
        }

        @Test
        void savingDefaultsBeforeAnyRead_stillBacksTheFlatConfigUp() throws IOException {
            writeLegacy(BREAKS, LEGACY_JSON);

            store().saveDefaults(BREAKS, Map.of("breakEvery", "30"));

            Path backup = legacyFile(BREAKS).resolveSibling(legacyFile(BREAKS).getFileName()
                    + ManagementSettingsStore.BACKUP_SUFFIX);
            assertAll(
                    () -> assertEquals(LEGACY_JSON, Files.readString(backup)),
                    () -> assertEquals("30", store().defaults(BREAKS, fields()).asMap().get("breakEvery")));
        }

        @Test
        void anotherScriptsFlatConfig_isLeftForThatScript() throws IOException {
            writeLegacy(BREAKS, LEGACY_JSON);
            writeLegacy("Fleet Monitor", "{\"interval\":\"5\"}");

            store().defaults(BREAKS, fields());

            assertTrue(Files.isRegularFile(legacyFile("Fleet Monitor")));
        }

        private List<Path> listing() throws IOException {
            List<Path> paths = new ArrayList<>();
            try (var walk = Files.walk(configDir)) {
                walk.sorted().forEach(paths::add);
            }
            return paths;
        }
    }
}
