package com.botwithus.bot.core.config;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * Keeps a management script's settings: the defaults every target uses, and
 * each target's own values.
 *
 * <p>Each script has a folder under {@code <config>/__management/}, holding
 * {@code defaults.json} and one file per target that has values of its own,
 * named after the target's key. A target file only ever holds the values that
 * differ from what the target would otherwise get; which values those are is
 * the caller's decision, since only it knows the targets.</p>
 *
 * <p>Hosts before per-target settings kept one flat file per management script,
 * {@code <config>/__management/<script>.json}. The first time a script's
 * settings are read or saved, that file becomes its {@code defaults.json} and is
 * then renamed to {@code <script>.json.bak}, so nothing is lost and the move is
 * made once. A script that already has {@code defaults.json} is never migrated
 * again.</p>
 *
 * <p>Reads come from memory after the first, so the inspector can ask every
 * frame. Saves update memory at once and write the file on {@code writer}; each
 * write takes the latest values, so two saves in quick succession cannot land
 * in the wrong order. Thread-safe.</p>
 */
public final class ManagementSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(ManagementSettingsStore.class);

    /** The folder under the config folder that management scripts' settings live in. */
    public static final String BUCKET = "__management";
    /** The name no target key may take, because the defaults' file has it. */
    static final String DEFAULTS_NAME = "defaults";
    static final String DEFAULTS_FILE = DEFAULTS_NAME + ".json";
    /** Added to a migrated flat config's name. */
    static final String BACKUP_SUFFIX = ".bak";
    /** Added to the name of a file that could not be read, when it is set aside. */
    static final String UNREADABLE_SUFFIX = ".unreadable";

    private static final String JSON = ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    /** Windows device names, which cannot be used as file names with any extension. */
    private static final Set<String> DEVICE_NAMES = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int BYTE_MASK = 0xFF;
    private static final String ESCAPE_FORMAT = "%%%02X";

    /** One settings file: a script's defaults, or one target's own values. */
    private record Slot(String script, Optional<String> targetKey) {

        boolean isDefaults() {
            return targetKey.isEmpty();
        }
    }

    private final Path root;
    private final Executor writer;
    /** Serialises the writes to disk. */
    private final Object writeLock = new Object();
    /** Guarded by {@code this}. */
    private final Map<Slot, Map<String, String>> cache = new HashMap<>();
    /** Scripts whose flat config has been looked at; guarded by {@code this}. */
    private final Set<String> migrationChecked = new HashSet<>();

    /**
     * @param configDir the host's config folder; the settings go in its
     *                  {@value #BUCKET} folder
     * @param writer    runs the file writes
     */
    public ManagementSettingsStore(Path configDir, Executor writer) {
        this.root = Objects.requireNonNull(configDir, "configDir").resolve(BUCKET);
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** A store over {@code ~/.botwithus/config}, writing on a virtual thread per save. */
    public static ManagementSettingsStore inUserHome() {
        Path configDir = Path.of(System.getProperty("user.home"), ".botwithus", "config");
        return new ManagementSettingsStore(configDir,
                task -> Thread.ofVirtual().name("management-settings-save").start(task));
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /**
     * {@code script}'s defaults: each of {@code fields} at its declared default,
     * overlaid with what the user saved. A saved key no field declares is kept.
     */
    public ScriptConfig defaults(String script, List<ConfigField> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            values.put(field.key(), field.defaultAsString());
        }
        values.putAll(savedDefaults(script));
        return new ScriptConfig(values);
    }

    /** The defaults the user saved for {@code script}, without the declared ones. */
    public synchronized Map<String, String> savedDefaults(String script) {
        migrateOnce(script);
        return cached(new Slot(script, Optional.empty()));
    }

    /** The values {@code targetKey} has of its own for {@code script}; empty if none. */
    public synchronized Map<String, String> overrides(String script, String targetKey) {
        migrateOnce(script);
        return cached(new Slot(script, Optional.of(checkedTarget(targetKey))));
    }

    // ── Saves ───────────────────────────────────────────────────────────────

    /** Replaces {@code script}'s saved defaults with {@code values}. */
    public void saveDefaults(String script, Map<String, String> values) {
        save(new Slot(script, Optional.empty()), values);
    }

    /**
     * Replaces the values {@code targetKey} has of its own for {@code script}.
     * Saving none deletes the target's file.
     *
     * @throws IllegalArgumentException if {@code targetKey} is the defaults' name
     */
    public void saveOverrides(String script, String targetKey, Map<String, String> values) {
        save(new Slot(script, Optional.of(checkedTarget(targetKey))), values);
    }

    private void save(Slot slot, Map<String, String> values) {
        synchronized (this) {
            migrateOnce(slot.script());
            cache.put(slot, Map.copyOf(values));
        }
        writer.execute(() -> writeLatest(slot));
    }

    private void writeLatest(Slot slot) {
        synchronized (writeLock) {
            Map<String, String> latest;
            synchronized (this) {
                latest = cache.get(slot);
            }
            Path file = fileOf(slot);
            try {
                if (latest.isEmpty() && !slot.isDefaults()) {
                    Files.deleteIfExists(file);
                } else {
                    AtomicFiles.write(file, GSON.toJson(new TreeMap<>(latest), MAP_TYPE)
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                log.error("Failed to save the management settings in {}", file, e);
            }
        }
    }

    // ── Files ───────────────────────────────────────────────────────────────

    /** {@code script}'s folder. Package-private for the tests. */
    Path scriptDir(String script) {
        return root.resolve(fileNameOf(script));
    }

    /** Where {@code targetKey}'s own values for {@code script} are kept. Package-private for the tests. */
    Path overridesFile(String script, String targetKey) {
        return fileOf(new Slot(script, Optional.of(checkedTarget(targetKey))));
    }

    private Path fileOf(Slot slot) {
        return scriptDir(slot.script()).resolve(slot.targetKey().map(ManagementSettingsStore::fileNameOf)
                .map(name -> name + JSON).orElse(DEFAULTS_FILE));
    }

    /**
     * A file name for {@code text} that is safe on Windows and different for
     * different text. Letters, digits, {@code -} and {@code _} are kept; every
     * other character, {@code %} included, becomes {@code %XX} per UTF-8 byte.
     * A Windows device name has its first letter escaped too. Windows compares
     * file names without regard to case, so two texts differing only in case
     * still share a file. Package-private for the tests.
     */
    static String fileNameOf(String text) {
        StringBuilder name = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & BYTE_MASK);
            if (isKept(c)) {
                name.append(c);
            } else {
                name.append(String.format(Locale.ROOT, ESCAPE_FORMAT, b & BYTE_MASK));
            }
        }
        if (DEVICE_NAMES.contains(name.toString().toUpperCase(Locale.ROOT))) {
            name.replace(0, 1, String.format(Locale.ROOT, ESCAPE_FORMAT, (int) name.charAt(0)));
        }
        return name.toString();
    }

    private static boolean isKept(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }

    private static String checkedTarget(String targetKey) {
        Objects.requireNonNull(targetKey, "targetKey");
        if (fileNameOf(targetKey).equalsIgnoreCase(DEFAULTS_NAME)) {
            throw new IllegalArgumentException("A target cannot be called \"" + DEFAULTS_NAME + "\"");
        }
        return targetKey;
    }

    /** The slot's values, reading its file the first time. Call holding {@code this}. */
    private Map<String, String> cached(Slot slot) {
        return cache.computeIfAbsent(slot, this::read);
    }

    private Map<String, String> read(Slot slot) {
        Path file = fileOf(slot);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, String> saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
            return saved != null ? Map.copyOf(withoutNulls(saved)) : Map.of();
        } catch (IOException | JsonParseException e) {
            log.error("Could not read the management settings in {}; setting it aside", file, e);
            setAside(file);
            return Map.of();
        }
    }

    private static Map<String, String> withoutNulls(Map<String, String> saved) {
        Map<String, String> values = new LinkedHashMap<>();
        saved.forEach((key, value) -> {
            if (key != null && value != null) {
                values.put(key, value);
            }
        });
        return values;
    }

    private static void setAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + UNREADABLE_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not set {} aside: {}", file, e.toString());
        }
    }

    // ── Migration ───────────────────────────────────────────────────────────

    /**
     * Makes {@code script}'s flat config from before per-target settings its
     * defaults, once. Call holding {@code this}.
     */
    private void migrateOnce(String script) {
        if (!migrationChecked.add(script)) {
            return;
        }
        Path legacy = root.resolve(ScriptConfigStore.safeName(script) + JSON);
        Path defaults = fileOf(new Slot(script, Optional.empty()));
        if (!Files.isRegularFile(legacy) || Files.exists(defaults)) {
            return;
        }
        try {
            AtomicFiles.write(defaults, Files.readAllBytes(legacy));
            Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + BACKUP_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Moved {}'s settings to {}; the old file is kept as {}{}", script, defaults,
                    legacy.getFileName(), BACKUP_SUFFIX);
        } catch (IOException e) {
            log.error("Failed to move {}'s settings from {}", script, legacy, e);
        }
    }
}
