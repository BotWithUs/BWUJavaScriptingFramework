package com.botwithus.bot.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptProfileStoreTest {

    private static final String UUID_ALICE = "acc-aaaa-bbbb";
    private static final String UUID_BOB = "acc-cccc-dddd";
    private static final String UUID_PLAYER_ONE = "acc-1111";
    private static final String UUID_PLAYER_TWO = "acc-2222";

    @TempDir
    Path tempDir;

    private Path baseDir;

    @BeforeEach
    void setUp() {
        baseDir = tempDir.resolve(".botwithus");
    }

    private ScriptProfileStore newStore() {
        return new ScriptProfileStore(baseDir);
    }

    // --- Account script persistence ---

    @Test
    void getAccountScripts_returnsEmptyForNewAccount() {
        ScriptProfileStore store = newStore();
        List<String> scripts = store.getAccountScripts(UUID_PLAYER_ONE);
        assertTrue(scripts.isEmpty());
    }

    @Test
    void setAndGetAccountScripts() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("WoodcuttingScript", "FishingScript"));

        // Read back with a fresh store instance to verify file persistence
        ScriptProfileStore store2 = newStore();
        List<String> scripts = store2.getAccountScripts(UUID_PLAYER_ONE);
        assertEquals(2, scripts.size());
        assertTrue(scripts.contains("WoodcuttingScript"));
        assertTrue(scripts.contains("FishingScript"));
    }

    @Test
    void setAccountScripts_overwritesPrevious() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("ScriptA", "ScriptB"));
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("ScriptC"));

        List<String> scripts = store.getAccountScripts(UUID_PLAYER_ONE);
        assertEquals(1, scripts.size());
        assertEquals("ScriptC", scripts.getFirst());
    }

    @Test
    void setAccountScripts_emptyListClearsScripts() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("ScriptA"));
        store.setAccountScripts(UUID_PLAYER_ONE, List.of());

        List<String> scripts = store.getAccountScripts(UUID_PLAYER_ONE);
        assertTrue(scripts.isEmpty());
    }

    // --- Auto-start flag ---

    @Test
    void isAutoStart_defaultsToTrue() {
        ScriptProfileStore store = newStore();
        assertTrue(store.isAutoStart("acc-fresh"));
    }

    @Test
    void setAndGetAutoStart() {
        ScriptProfileStore store = newStore();
        store.setAutoStart(UUID_PLAYER_ONE, false);

        ScriptProfileStore store2 = newStore();
        assertFalse(store2.isAutoStart(UUID_PLAYER_ONE));
    }

    @Test
    void setAutoStart_toggle() {
        ScriptProfileStore store = newStore();
        store.setAutoStart(UUID_PLAYER_ONE, false);
        assertFalse(store.isAutoStart(UUID_PLAYER_ONE));
        store.setAutoStart(UUID_PLAYER_ONE, true);
        assertTrue(store.isAutoStart(UUID_PLAYER_ONE));
    }

    // --- Display name hint ---

    @Test
    void setDisplayName_persistsAsHintReadableViaSummary() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("Foo"));
        store.setDisplayName(UUID_PLAYER_ONE, "PlayerOne");

        ScriptProfileStore store2 = newStore();
        Map<String, ScriptProfileStore.ProfileSummary> summaries = store2.listAccountProfiles();
        ScriptProfileStore.ProfileSummary summary = summaries.get(UUID_PLAYER_ONE);
        assertNotNull(summary);
        assertEquals("PlayerOne", summary.displayName());
        assertEquals(List.of("Foo"), summary.scripts());
    }

    @Test
    void getDisplayName_defaultsToEmpty() {
        ScriptProfileStore store = newStore();
        assertEquals("", store.getDisplayName(UUID_PLAYER_ONE));
    }

    // --- Group scripts ---

    @Test
    void getGroupScripts_returnsEmptyForNewGroup() {
        ScriptProfileStore store = newStore();
        assertTrue(store.getGroupScripts("farm1").isEmpty());
    }

    @Test
    void setAndGetGroupScripts() {
        ScriptProfileStore store = newStore();
        store.setGroupScripts("farm1", List.of("WoodcuttingScript"));

        ScriptProfileStore store2 = newStore();
        List<String> scripts = store2.getGroupScripts("farm1");
        assertEquals(1, scripts.size());
        assertEquals("WoodcuttingScript", scripts.getFirst());
    }

    @Test
    void isGroupAutoStart_defaultsToTrue() {
        ScriptProfileStore store = newStore();
        assertTrue(store.isGroupAutoStart("farm1"));
    }

    @Test
    void setAndGetGroupAutoStart() {
        ScriptProfileStore store = newStore();
        store.setGroupAutoStart("farm1", false);

        ScriptProfileStore store2 = newStore();
        assertFalse(store2.isGroupAutoStart("farm1"));
    }

    // --- Multiple accounts ---

    @Test
    void multipleAccountsAreSeparate() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("ScriptA"));
        store.setAccountScripts(UUID_PLAYER_TWO, List.of("ScriptB", "ScriptC"));

        assertEquals(List.of("ScriptA"), store.getAccountScripts(UUID_PLAYER_ONE));
        assertEquals(List.of("ScriptB", "ScriptC"), store.getAccountScripts(UUID_PLAYER_TWO));
    }

    // --- Listing profiles ---

    @Test
    void listAccountProfiles_empty() {
        ScriptProfileStore store = newStore();
        assertTrue(store.listAccountProfiles().isEmpty());
    }

    @Test
    void listAccountProfiles_returnsAllAccounts() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_ALICE, List.of("Script1"));
        store.setDisplayName(UUID_ALICE, "Alice");
        store.setAccountScripts(UUID_BOB, List.of("Script2", "Script3"));
        store.setDisplayName(UUID_BOB, "Bob");

        Map<String, ScriptProfileStore.ProfileSummary> profiles = store.listAccountProfiles();
        assertEquals(2, profiles.size());
        assertTrue(profiles.containsKey(UUID_ALICE));
        assertTrue(profiles.containsKey(UUID_BOB));
        assertEquals(List.of("Script1"), profiles.get(UUID_ALICE).scripts());
        assertEquals("Alice", profiles.get(UUID_ALICE).displayName());
        assertEquals(List.of("Script2", "Script3"), profiles.get(UUID_BOB).scripts());
        assertEquals("Bob", profiles.get(UUID_BOB).displayName());
    }

    @Test
    void listGroupProfiles_returnsAllGroups() {
        ScriptProfileStore store = newStore();
        store.setGroupScripts("farm1", List.of("WoodcuttingScript"));
        store.setGroupScripts("farm2", List.of("FishingScript"));

        Map<String, List<String>> groups = store.listGroupProfiles();
        assertEquals(2, groups.size());
        assertTrue(groups.containsKey("farm1"));
        assertTrue(groups.containsKey("farm2"));
    }

    // --- Clear profile ---

    @Test
    void clearAccountProfile_removesFile() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts(UUID_PLAYER_ONE, List.of("Script1"));
        assertFalse(store.getAccountScripts(UUID_PLAYER_ONE).isEmpty());

        assertTrue(store.clearAccountProfile(UUID_PLAYER_ONE));
        assertTrue(store.getAccountScripts(UUID_PLAYER_ONE).isEmpty());
    }

    @Test
    void clearAccountProfile_returnsFalseForNonExistent() {
        ScriptProfileStore store = newStore();
        assertFalse(store.clearAccountProfile("acc-nobody"));
    }

    // --- Global settings ---

    @Test
    void isAutoConnect_defaultsTrue() {
        ScriptProfileStore store = newStore();
        assertTrue(store.isAutoConnect());
    }

    @Test
    void setAndGetAutoConnect() {
        ScriptProfileStore store = newStore();
        store.setAutoConnect(true);
        store.saveSettings();

        ScriptProfileStore store2 = newStore();
        assertTrue(store2.isAutoConnect());
    }

    @Test
    void getPipePrefix_default() {
        ScriptProfileStore store = newStore();
        assertEquals("BotWithUs", store.getPipePrefix());
    }

    @Test
    void setAndGetPipePrefix() {
        ScriptProfileStore store = newStore();
        store.setPipePrefix("CustomPipe");
        store.saveSettings();

        ScriptProfileStore store2 = newStore();
        assertEquals("CustomPipe", store2.getPipePrefix());
    }

    @Test
    void isProbeLobby_defaultsTrue() {
        ScriptProfileStore store = newStore();
        assertTrue(store.isProbeLobby());
    }

    @Test
    void getScanIntervalMs_default() {
        ScriptProfileStore store = newStore();
        assertEquals(5000, store.getScanIntervalMs());
    }

    @Test
    void getScanIntervalMs_handlesInvalidValue() {
        // Write a bad value manually
        ScriptProfileStore store = newStore();
        store.getSettings().setProperty("scanIntervalMs", "notAnumber");
        assertEquals(5000, store.getScanIntervalMs());
    }

    // --- Name sanitization ---

    @Test
    void accountKeyWithSpecialCharsIsSanitized() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("uuid with spaces!", List.of("Script1"));

        // Should still be retrievable with the same key
        List<String> scripts = store.getAccountScripts("uuid with spaces!");
        assertEquals(List.of("Script1"), scripts);
    }

    // --- Profile file is created in correct directory ---

    @Test
    void profileFileIsCreatedUnderBotwithus() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("acc-testplayer", List.of("MyScript"));

        Path profilesDir = baseDir.resolve("profiles");
        assertTrue(Files.isDirectory(profilesDir));
        assertTrue(Files.exists(profilesDir.resolve("acc-testplayer.properties")));
    }

    @Test
    void groupFileIsCreatedUnderGroupsDir() {
        ScriptProfileStore store = newStore();
        store.setGroupScripts("mygroup", List.of("Script1"));

        Path groupsDir = baseDir.resolve("profiles").resolve("groups");
        assertTrue(Files.isDirectory(groupsDir));
        assertTrue(Files.exists(groupsDir.resolve("mygroup.properties")));
    }

    @Test
    void settingsFileIsCreated() {
        ScriptProfileStore store = newStore();
        store.setAutoConnect(true);
        store.saveSettings();

        Path settingsFile = baseDir.resolve("autostart.properties");
        assertTrue(Files.exists(settingsFile));
    }

    // --- One-shot hard-cut migration ---

    @Test
    void migration_wipesLegacyFiles_andTouchesSentinel() throws IOException {
        Path profilesDir = baseDir.resolve("profiles");
        Path groupsDir = profilesDir.resolve("groups");
        Path configDir = baseDir.resolve("config");
        Files.createDirectories(groupsDir);
        Files.createDirectories(configDir);

        Path legacyProfile = profilesDir.resolve("Old.properties");
        Path legacyConfigJson = configDir.resolve("SomeScript.json");
        Path legacyConfigProps = configDir.resolve("Other.properties");
        Path keepGroup = groupsDir.resolve("farm.properties");
        Files.writeString(legacyProfile, "scripts=Foo\n");
        Files.writeString(legacyConfigJson, "{}\n");
        Files.writeString(legacyConfigProps, "k=v\n");
        Files.writeString(keepGroup, "scripts=Bar\n");

        // Constructing the store triggers the cleanup.
        newStore();

        Path sentinel = baseDir.resolve(".account-uuid-keying-v1");
        assertTrue(Files.exists(sentinel), "sentinel must be written");
        assertFalse(Files.exists(legacyProfile), "legacy display-name profile must be deleted");
        assertFalse(Files.exists(legacyConfigJson), "legacy config json must be deleted");
        assertFalse(Files.exists(legacyConfigProps), "legacy config properties must be deleted");
        assertTrue(Files.exists(keepGroup), "group profile must be preserved");
    }

    @Test
    void migration_isIdempotent_andLeavesNewLayoutAlone() throws IOException {
        Path profilesDir = baseDir.resolve("profiles");
        Path configDir = baseDir.resolve("config");
        Path sentinel = baseDir.resolve(".account-uuid-keying-v1");
        Files.createDirectories(profilesDir);
        Files.createDirectories(configDir);
        Files.createFile(sentinel);

        // The sentinel exists, so a fresh post-migration uuid-keyed profile
        // and a per-account config subdir must survive a second construction.
        Path uuidProfile = profilesDir.resolve("acc-aaaa.properties");
        Files.writeString(uuidProfile, "scripts=Foo\n");
        Path uuidConfigDir = configDir.resolve("acc-aaaa");
        Files.createDirectories(uuidConfigDir);
        Path uuidConfigJson = uuidConfigDir.resolve("Bar.json");
        Files.writeString(uuidConfigJson, "{}\n");

        newStore();

        assertTrue(Files.exists(sentinel));
        assertTrue(Files.exists(uuidProfile), "new uuid-keyed profile must survive idempotent run");
        assertTrue(Files.exists(uuidConfigJson), "per-account config subdir must survive");
    }
}
