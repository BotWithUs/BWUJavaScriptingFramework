package com.botwithus.bot.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Persists per-account and per-group script auto-start profiles
 * as {@code .properties} files in {@code ~/.botwithus/profiles/}.
 *
 * <p>Account profiles: {@code ~/.botwithus/profiles/<AccountName>.properties}
 * <p>Group profiles: {@code ~/.botwithus/profiles/groups/<GroupName>.properties}
 * <p>Global settings: {@code ~/.botwithus/autostart.properties}
 */
public final class ScriptProfileStore {

    private static final Logger log = LoggerFactory.getLogger(ScriptProfileStore.class);
    private final Path baseDir;
    private final Path profilesDir;
    private final Path groupsDir;
    private final Path settingsFile;
    private final Properties globalSettings = new Properties();

    public ScriptProfileStore() {
        this(Path.of(System.getProperty("user.home"), ".botwithus"));
    }

    public ScriptProfileStore(Path baseDir) {
        this.baseDir = baseDir;
        this.profilesDir = baseDir.resolve("profiles");
        this.groupsDir = profilesDir.resolve("groups");
        this.settingsFile = baseDir.resolve("autostart.properties");
        loadSettings();
    }

    // --- Global settings ---

    private void loadSettings() {
        if (!Files.exists(settingsFile)) return;
        try (Reader r = Files.newBufferedReader(settingsFile)) {
            globalSettings.load(r);
        } catch (IOException e) {
            log.error("Failed to load settings: {}", e.getMessage());
        }
    }

    public void saveSettings() {
        try {
            Files.createDirectories(baseDir);
            try (Writer w = Files.newBufferedWriter(settingsFile)) {
                globalSettings.store(w, "JBotWithUs Auto-Start Settings");
            }
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());
        }
    }

    public boolean isAutoConnect() {
        return Boolean.parseBoolean(globalSettings.getProperty("autoConnect", "false"));
    }

    public void setAutoConnect(boolean enabled) {
        globalSettings.setProperty("autoConnect", String.valueOf(enabled));
    }

    public String getPipePrefix() {
        return globalSettings.getProperty("pipePrefix", "BotWithUs");
    }

    public void setPipePrefix(String prefix) {
        globalSettings.setProperty("pipePrefix", prefix);
    }

    public boolean isProbeLobby() {
        return Boolean.parseBoolean(globalSettings.getProperty("probeLobby", "true"));
    }

    public long getScanIntervalMs() {
        try {
            return Long.parseLong(globalSettings.getProperty("scanIntervalMs", "5000"));
        } catch (NumberFormatException e) {
            return 5000;
        }
    }

    public Properties getSettings() {
        return globalSettings;
    }

    // --- Per-account profiles ---

    public List<String> getAccountScripts(String displayName) {
        Properties props = loadProfile(accountFile(displayName));
        String scripts = props.getProperty("scripts", "");
        if (scripts.isBlank()) return List.of();
        return Arrays.stream(scripts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setAccountScripts(String displayName, List<String> scripts) {
        Properties props = loadProfile(accountFile(displayName));
        props.setProperty("scripts", String.join(",", scripts));
        saveProfile(accountFile(displayName), props, "Profile for account: " + displayName);
    }

    public boolean isAutoStart(String displayName) {
        Properties props = loadProfile(accountFile(displayName));
        return Boolean.parseBoolean(props.getProperty("autoStart", "true"));
    }

    public void setAutoStart(String displayName, boolean enabled) {
        Properties props = loadProfile(accountFile(displayName));
        props.setProperty("autoStart", String.valueOf(enabled));
        saveProfile(accountFile(displayName), props, "Profile for account: " + displayName);
    }

    // --- Per-group profiles ---

    public List<String> getGroupScripts(String groupName) {
        Properties props = loadProfile(groupFile(groupName));
        String scripts = props.getProperty("scripts", "");
        if (scripts.isBlank()) return List.of();
        return Arrays.stream(scripts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setGroupScripts(String groupName, List<String> scripts) {
        Properties props = loadProfile(groupFile(groupName));
        props.setProperty("scripts", String.join(",", scripts));
        saveProfile(groupFile(groupName), props, "Profile for group: " + groupName);
    }

    public boolean isGroupAutoStart(String groupName) {
        Properties props = loadProfile(groupFile(groupName));
        return Boolean.parseBoolean(props.getProperty("autoStart", "true"));
    }

    public void setGroupAutoStart(String groupName, boolean enabled) {
        Properties props = loadProfile(groupFile(groupName));
        props.setProperty("autoStart", String.valueOf(enabled));
        saveProfile(groupFile(groupName), props, "Profile for group: " + groupName);
    }

    // --- Listing ---

    /**
     * Returns a map of account display name to their configured script list.
     */
    public Map<String, List<String>> listAccountProfiles() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(profilesDir)) return result;
        try (Stream<Path> files = Files.list(profilesDir)) {
            files.filter(p -> p.toString().endsWith(".properties") && Files.isRegularFile(p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        name = name.substring(0, name.length() - ".properties".length());
                        result.put(name, getAccountScripts(name));
                    });
        } catch (IOException e) {
            log.error("Failed to list profiles: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Returns a map of group name to their configured script list.
     */
    public Map<String, List<String>> listGroupProfiles() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(groupsDir)) return result;
        try (Stream<Path> files = Files.list(groupsDir)) {
            files.filter(p -> p.toString().endsWith(".properties") && Files.isRegularFile(p))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        name = name.substring(0, name.length() - ".properties".length());
                        result.put(name, getGroupScripts(name));
                    });
        } catch (IOException e) {
            log.error("Failed to list group profiles: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Removes a profile for the given account display name.
     */
    public boolean clearAccountProfile(String displayName) {
        Path file = accountFile(displayName);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to clear profile: {}", e.getMessage());
            return false;
        }
    }

    // --- Internal helpers ---

    private Path accountFile(String displayName) {
        String safe = sanitize(displayName);
        return profilesDir.resolve(safe + ".properties");
    }

    private Path groupFile(String groupName) {
        String safe = sanitize(groupName);
        return groupsDir.resolve(safe + ".properties");
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static Properties loadProfile(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                props.load(r);
            } catch (IOException e) {
                log.error("Failed to load {}: {}", file, e.getMessage());
            }
        }
        return props;
    }

    private static void saveProfile(Path file, Properties props, String comment) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                props.store(w, comment);
            }
        } catch (IOException e) {
            log.error("Failed to save {}: {}", file, e.getMessage());
        }
    }
}
