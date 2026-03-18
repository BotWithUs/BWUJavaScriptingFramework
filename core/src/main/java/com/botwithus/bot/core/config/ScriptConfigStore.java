package com.botwithus.bot.core.config;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists script configuration as JSON files using Gson
 * in {@code ~/.botwithus/config/}.
 */
public final class ScriptConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ScriptConfigStore.class);
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".botwithus", "config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

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
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<String, String> saved = GSON.fromJson(reader, MAP_TYPE);
                if (saved != null) {
                    values.putAll(saved);
                }
            } catch (IOException e) {
                log.error("Failed to load config for {}: {}", scriptName, e.getMessage());
            }
        }

        // Migrate legacy .properties file if it exists and no JSON file was found
        if (!Files.exists(file)) {
            Path legacyFile = CONFIG_DIR.resolve(safeName(scriptName) + ".properties");
            if (Files.exists(legacyFile)) {
                var props = new java.util.Properties();
                try (Reader reader = Files.newBufferedReader(legacyFile)) {
                    props.load(reader);
                    for (String key : props.stringPropertyNames()) {
                        values.put(key, props.getProperty(key));
                    }
                } catch (IOException e) {
                    // ignore migration failure
                }
            }
        }

        return new ScriptConfig(values);
    }

    public static void save(String scriptName, ScriptConfig config) {
        try {
            Files.createDirectories(CONFIG_DIR);
            Path file = configFile(scriptName);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(config.asMap(), MAP_TYPE, writer);
            }
        } catch (IOException e) {
            log.error("Failed to save config for {}: {}", scriptName, e.getMessage());
        }
    }

    private static Path configFile(String scriptName) {
        return CONFIG_DIR.resolve(safeName(scriptName) + ".json");
    }

    private static String safeName(String scriptName) {
        return scriptName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
