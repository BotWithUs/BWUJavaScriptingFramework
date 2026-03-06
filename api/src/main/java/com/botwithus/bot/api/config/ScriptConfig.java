package com.botwithus.bot.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of a script's configuration values.
 * Passed to {@link com.botwithus.bot.api.BotScript#onConfigUpdate(ScriptConfig)}.
 */
public final class ScriptConfig {

    private final Map<String, String> values;

    public ScriptConfig(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    /**
     * Returns the integer value for a key, or the default if absent or unparseable.
     *
     * @param key          the config key
     * @param defaultValue the fallback value
     * @return the integer value
     */
    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns the string value for a key, or the default if absent.
     *
     * @param key          the config key
     * @param defaultValue the fallback value
     * @return the string value
     */
    public String getString(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the boolean value for a key, or the default if absent.
     *
     * @param key          the config key
     * @param defaultValue the fallback value
     * @return the boolean value
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }

    /**
     * Returns an unmodifiable view of all config key-value pairs.
     *
     * @return the config map
     */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
