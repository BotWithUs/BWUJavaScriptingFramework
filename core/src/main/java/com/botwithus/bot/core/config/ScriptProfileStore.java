package com.botwithus.bot.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Persists per-account and per-group script auto-start profiles
 * as {@code .properties} files in {@code ~/.botwithus/profiles/}.
 *
 * <p>Account profiles are keyed by the stable {@code account_uuid} surfaced by
 * the agent's {@code get_account_info} (originating from the loader's
 * {@code BotDetails}); the in-game display name is persisted as a hint inside
 * the file so UI surfaces can render a human-readable label.
 *
 * <p>Account profiles: {@code ~/.botwithus/profiles/<accountUuid>.properties}
 * <p>Group profiles: {@code ~/.botwithus/profiles/groups/<GroupName>.properties}
 * <p>Global settings: {@code ~/.botwithus/autostart.properties}
 */
public final class ScriptProfileStore {

    private static final Logger log = LoggerFactory.getLogger(ScriptProfileStore.class);
    private static final long DEFAULT_SCAN_INTERVAL_MS = 5000L;
    private static final String DEFAULT_SCAN_INTERVAL_MS_STR = Long.toString(DEFAULT_SCAN_INTERVAL_MS);
    private final Path baseDir;
    private final Path profilesDir;
    private final Path groupsDir;
    private final Path settingsFile;
    private final Properties globalSettings = new Properties();

    /**
     * Human-readable summary for an account profile, surfaced through
     * {@link #listAccountProfiles()} so UI/CLI callers can show the in-game
     * display name without having to read each profile file themselves.
     *
     * <p>{@code displayName} is the rendered label; {@code scripts} is the
     * auto-start list; {@code autoStart} is the on/off toggle. An empty
     * {@code displayName} means the profile has not yet been tagged with
     * a name — fall back to the uuid for rendering.
     */
    public record ProfileSummary(String displayName, List<String> scripts, boolean autoStart) {}

    public ScriptProfileStore() {
        this(Path.of(System.getProperty("user.home"), ".botwithus"));
    }

    public ScriptProfileStore(Path baseDir) {
        this.baseDir = baseDir;
        this.profilesDir = baseDir.resolve("profiles");
        this.groupsDir = profilesDir.resolve("groups");
        this.settingsFile = baseDir.resolve("autostart.properties");
        LegacyConfigCleanup.runIfNeeded(baseDir);
        loadSettings();
    }

    // --- Global settings ---

    private void loadSettings() {
        if (!Files.exists(settingsFile)) {
            return;
        }
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
        return Boolean.parseBoolean(globalSettings.getProperty("autoConnect", "true"));
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
            return Long.parseLong(globalSettings.getProperty("scanIntervalMs", DEFAULT_SCAN_INTERVAL_MS_STR));
        } catch (NumberFormatException e) {
            return DEFAULT_SCAN_INTERVAL_MS;
        }
    }

    public Properties getSettings() {
        return globalSettings;
    }

    // --- Per-account profiles ---

    public List<String> getAccountScripts(String accountUuid) {
        Properties props = loadProfile(accountFile(accountUuid));
        String scripts = props.getProperty("scripts", "");
        if (scripts.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scripts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setAccountScripts(String accountUuid, List<String> scripts) {
        Properties props = loadProfile(accountFile(accountUuid));
        props.setProperty("scripts", String.join(",", scripts));
        saveProfile(accountFile(accountUuid), props, profileComment(accountUuid));
    }

    public boolean isAutoStart(String accountUuid) {
        Properties props = loadProfile(accountFile(accountUuid));
        return Boolean.parseBoolean(props.getProperty("autoStart", "true"));
    }

    public void setAutoStart(String accountUuid, boolean enabled) {
        Properties props = loadProfile(accountFile(accountUuid));
        props.setProperty("autoStart", String.valueOf(enabled));
        saveProfile(accountFile(accountUuid), props, profileComment(accountUuid));
    }

    /**
     * Persists the in-game display name as a hint on the profile keyed by
     * {@code accountUuid}. The hint is purely for human-readable rendering;
     * the uuid remains the storage key. Safe to call with an empty
     * {@code displayName} (the field is then cleared).
     */
    public void setDisplayName(String accountUuid, String displayName) {
        Properties props = loadProfile(accountFile(accountUuid));
        props.setProperty("displayName", displayName == null ? "" : displayName);
        saveProfile(accountFile(accountUuid), props, profileComment(accountUuid));
    }

    public String getDisplayName(String accountUuid) {
        Properties props = loadProfile(accountFile(accountUuid));
        return props.getProperty("displayName", "");
    }

    // --- Per-group profiles ---

    public List<String> getGroupScripts(String groupName) {
        Properties props = loadProfile(groupFile(groupName));
        String scripts = props.getProperty("scripts", "");
        if (scripts.isBlank()) {
            return List.of();
        }
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
     * Returns the registered account profiles keyed by stable {@code account_uuid}.
     * Each summary carries the in-game display-name hint, the configured script
     * list, and the auto-start toggle.
     */
    public Map<String, ProfileSummary> listAccountProfiles() {
        Map<String, ProfileSummary> result = new LinkedHashMap<>();
        if (!Files.isDirectory(profilesDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(profilesDir)) {
            files.filter(p -> p.toString().endsWith(".properties") && Files.isRegularFile(p))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String uuid = fileName.substring(0, fileName.length() - ".properties".length());
                        Properties props = loadProfile(p);
                        String displayName = props.getProperty("displayName", "");
                        String scriptsCsv = props.getProperty("scripts", "");
                        List<String> scripts = scriptsCsv.isBlank() ? List.of()
                                : Arrays.stream(scriptsCsv.split(","))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .toList();
                        boolean autoStart = Boolean.parseBoolean(props.getProperty("autoStart", "true"));
                        result.put(uuid, new ProfileSummary(displayName, scripts, autoStart));
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
        if (!Files.isDirectory(groupsDir)) {
            return result;
        }
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
     * Removes the profile keyed by {@code accountUuid}.
     */
    public boolean clearAccountProfile(String accountUuid) {
        Path file = accountFile(accountUuid);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to clear profile: {}", e.getMessage());
            return false;
        }
    }

    // --- Internal helpers ---

    private Path accountFile(String accountUuid) {
        String safe = sanitize(accountUuid);
        return profilesDir.resolve(safe + ".properties");
    }

    private Path groupFile(String groupName) {
        String safe = sanitize(groupName);
        return groupsDir.resolve(safe + ".properties");
    }

    private static String profileComment(String accountUuid) {
        return "Profile for account: " + accountUuid;
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
