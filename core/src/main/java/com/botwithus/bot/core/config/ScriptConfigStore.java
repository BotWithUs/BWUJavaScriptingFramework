package com.botwithus.bot.core.config;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Persists script configuration as {@code .properties} files
 * in {@code ~/.botwithus/config/}.
 */
public final class ScriptConfigStore {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".botwithus", "config");

    private ScriptConfigStore() {}

    /**
     * Loads persisted config for a script, falling back to field defaults for missing keys.
     *
     * @param scriptName the script name (used as filename)
     * @param fields     the declared config fields with defaults
     * @return the loaded config
     */
    public static ScriptConfig load(String scriptName, List<ConfigField> fields) {
        Map<String, String> values = new LinkedHashMap<>();

        for (ConfigField field : fields) {
            values.put(field.key(), String.valueOf(field.defaultValue()));
        }

        Path file = configFile(scriptName);
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("[ScriptConfigStore] Failed to load config for " + scriptName + ": " + e.getMessage());
            }
            for (String key : props.stringPropertyNames()) {
                values.put(key, props.getProperty(key));
            }
        }

        return new ScriptConfig(values);
    }

    public static void save(String scriptName, ScriptConfig config) {
        try {
            Files.createDirectories(CONFIG_DIR);
            Path file = configFile(scriptName);
            Properties props = new Properties();
            props.putAll(config.asMap());
            try (Writer writer = Files.newBufferedWriter(file)) {
                props.store(writer, "Config for script: " + scriptName);
            }
        } catch (IOException e) {
            System.err.println("[ScriptConfigStore] Failed to save config for " + scriptName + ": " + e.getMessage());
        }
    }

    private static Path configFile(String scriptName) {
        // Sanitize name for filesystem
        String safe = scriptName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return CONFIG_DIR.resolve(safe + ".properties");
    }
}
