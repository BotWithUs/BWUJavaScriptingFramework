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
 * Persists script configuration as JSON files in
 * {@code ~/.botwithus/config/<accountUuid>/<scriptName>.json}.
 *
 * <p>Per-account bucketing means two characters running the same script no
 * longer share one settings file. Management scripts (cross-client by design)
 * pass the literal {@code "__management"} as the {@code accountUuid} so they
 * land in a dedicated subdirectory.
 */
public final class ScriptConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ScriptConfigStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private ScriptConfigStore() {}

    /**
     * Resolved per call so tests can override {@code user.home} (the constant
     * pattern would otherwise freeze the path at class-load time and route
     * every test into the dev's real home directory).
     */
    private static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".botwithus", "config");
    }

    /**
     * Loads persisted config for a script under the given account bucket,
     * falling back to field defaults for missing keys.
     *
     * @param scriptName   the script name (used as filename)
     * @param accountUuid  the stable account-uuid bucket the config lives under
     * @param fields       the declared config fields with defaults
     * @return the loaded config
     */
    public static ScriptConfig load(String scriptName, String accountUuid, List<ConfigField> fields) {
        Map<String, String> values = new LinkedHashMap<>();

        for (ConfigField field : fields) {
            values.put(field.key(), field.defaultAsString());
        }

        Path file = configFile(scriptName, accountUuid);
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

        return new ScriptConfig(values);
    }

    public static void save(String scriptName, String accountUuid, ScriptConfig config) {
        try {
            Path file = configFile(scriptName, accountUuid);
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(config.asMap(), MAP_TYPE, writer);
            }
        } catch (IOException e) {
            log.error("Failed to save config for {}: {}", scriptName, e.getMessage());
        }
    }

    private static Path configFile(String scriptName, String accountUuid) {
        return configDir().resolve(safeName(accountUuid))
                .resolve(safeName(scriptName) + ".json");
    }

    private static String safeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
