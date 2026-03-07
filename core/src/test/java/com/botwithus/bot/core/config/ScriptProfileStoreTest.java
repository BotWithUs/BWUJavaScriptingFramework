package com.botwithus.bot.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptProfileStoreTest {

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
        List<String> scripts = store.getAccountScripts("PlayerOne");
        assertTrue(scripts.isEmpty());
    }

    @Test
    void setAndGetAccountScripts() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("PlayerOne", List.of("WoodcuttingScript", "FishingScript"));

        // Read back with a fresh store instance to verify file persistence
        ScriptProfileStore store2 = newStore();
        List<String> scripts = store2.getAccountScripts("PlayerOne");
        assertEquals(2, scripts.size());
        assertTrue(scripts.contains("WoodcuttingScript"));
        assertTrue(scripts.contains("FishingScript"));
    }

    @Test
    void setAccountScripts_overwritesPrevious() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("PlayerOne", List.of("ScriptA", "ScriptB"));
        store.setAccountScripts("PlayerOne", List.of("ScriptC"));

        List<String> scripts = store.getAccountScripts("PlayerOne");
        assertEquals(1, scripts.size());
        assertEquals("ScriptC", scripts.getFirst());
    }

    @Test
    void setAccountScripts_emptyListClearsScripts() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("PlayerOne", List.of("ScriptA"));
        store.setAccountScripts("PlayerOne", List.of());

        List<String> scripts = store.getAccountScripts("PlayerOne");
        assertTrue(scripts.isEmpty());
    }

    // --- Auto-start flag ---

    @Test
    void isAutoStart_defaultsToTrue() {
        ScriptProfileStore store = newStore();
        assertTrue(store.isAutoStart("NewPlayer"));
    }

    @Test
    void setAndGetAutoStart() {
        ScriptProfileStore store = newStore();
        store.setAutoStart("PlayerOne", false);

        ScriptProfileStore store2 = newStore();
        assertFalse(store2.isAutoStart("PlayerOne"));
    }

    @Test
    void setAutoStart_toggle() {
        ScriptProfileStore store = newStore();
        store.setAutoStart("PlayerOne", false);
        assertFalse(store.isAutoStart("PlayerOne"));
        store.setAutoStart("PlayerOne", true);
        assertTrue(store.isAutoStart("PlayerOne"));
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
        store.setAccountScripts("PlayerOne", List.of("ScriptA"));
        store.setAccountScripts("PlayerTwo", List.of("ScriptB", "ScriptC"));

        assertEquals(List.of("ScriptA"), store.getAccountScripts("PlayerOne"));
        assertEquals(List.of("ScriptB", "ScriptC"), store.getAccountScripts("PlayerTwo"));
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
        store.setAccountScripts("Alice", List.of("Script1"));
        store.setAccountScripts("Bob", List.of("Script2", "Script3"));

        Map<String, List<String>> profiles = store.listAccountProfiles();
        assertEquals(2, profiles.size());
        assertTrue(profiles.containsKey("Alice"));
        assertTrue(profiles.containsKey("Bob"));
        assertEquals(List.of("Script1"), profiles.get("Alice"));
        assertEquals(List.of("Script2", "Script3"), profiles.get("Bob"));
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
        store.setAccountScripts("PlayerOne", List.of("Script1"));
        assertFalse(store.getAccountScripts("PlayerOne").isEmpty());

        assertTrue(store.clearAccountProfile("PlayerOne"));
        assertTrue(store.getAccountScripts("PlayerOne").isEmpty());
    }

    @Test
    void clearAccountProfile_returnsFalseForNonExistent() {
        ScriptProfileStore store = newStore();
        assertFalse(store.clearAccountProfile("Nobody"));
    }

    // --- Global settings ---

    @Test
    void isAutoConnect_defaultsFalse() {
        ScriptProfileStore store = newStore();
        assertFalse(store.isAutoConnect());
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
    void accountNameWithSpecialCharsIsSanitized() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("Player One!", List.of("Script1"));

        // Should still be retrievable with the same key
        List<String> scripts = store.getAccountScripts("Player One!");
        assertEquals(List.of("Script1"), scripts);
    }

    // --- Profile file is created in correct directory ---

    @Test
    void profileFileIsCreatedUnderBotwithus() {
        ScriptProfileStore store = newStore();
        store.setAccountScripts("TestPlayer", List.of("MyScript"));

        Path profilesDir = baseDir.resolve("profiles");
        assertTrue(Files.isDirectory(profilesDir));
        assertTrue(Files.exists(profilesDir.resolve("TestPlayer.properties")));
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
}
