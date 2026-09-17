package com.botwithus.bot.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of a script's configuration values.
 * Passed to {@link com.botwithus.bot.api.BotScript#onConfigUpdate(ScriptConfig)}.
 *
 * @param values raw key/value map; defensively copied by the compact constructor
 */
public record ScriptConfig(Map<String, String> values) {

    public ScriptConfig {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Falls back to default if absent or unparseable. */
    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getString(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v);
    }

    /** Returns an unmodifiable view of the raw values. */
    public Map<String, String> asMap() {
        return values;
    }
}
