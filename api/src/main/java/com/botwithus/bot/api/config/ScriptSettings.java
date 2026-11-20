package com.botwithus.bot.api.config;

import java.util.Map;
import java.util.Optional;

/**
 * A script's own key/value settings, writable at runtime and saved per account. Obtained from
 * {@link com.botwithus.bot.api.ScriptContext#getSettings()}.
 *
 * <p>These are the same values {@link ScriptConfig} carries: a key a script declares as a
 * {@link ConfigField} shows in the UI and is in here too, and a key a script puts here that it
 * never declared is kept beside them and saved with them, but not shown. Changes are held in
 * memory until {@link #save()}; the runtime saves the declared fields itself when the user
 * applies them in the UI, and that save carries every other key along.</p>
 *
 * <p>Implementations are safe to use from any thread.</p>
 */
public interface ScriptSettings {

    /** The value stored under {@code key}, or empty when there is none. */
    Optional<String> get(String key);

    /** Stores {@code value} under {@code key}, replacing any value there. */
    void put(String key, String value);

    /**
     * Removes {@code key}.
     *
     * @return {@code true} when the key was there
     */
    boolean remove(String key);

    /** Every stored key and value, as an unmodifiable copy. */
    Map<String, String> asMap();

    /**
     * Writes the settings to the script's per-account settings file. Does nothing, with a log
     * line, when the runtime has no account to save under; the values stay in memory.
     */
    void save();

    /** The value under {@code key}, or {@code defaultValue} when there is none. */
    default String getString(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /** The value under {@code key} as an int, or {@code defaultValue} when absent or not one. */
    default int getInt(String key, int defaultValue) {
        return new ScriptConfig(asMap()).getInt(key, defaultValue);
    }

    /** The value under {@code key} as a boolean, or {@code defaultValue} when absent. */
    default boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    default void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    default void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    /**
     * Settings held in memory only: {@link #save()} keeps nothing. What a context the runtime
     * has not bound hands out.
     */
    static ScriptSettings inMemory() {
        return new InMemoryScriptSettings();
    }
}
