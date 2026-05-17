package com.botwithus.bot.api.config;

import java.util.List;

/**
 * Describes a single configurable parameter that a script exposes.
 * Scripts return a list of these from {@link com.botwithus.bot.api.BotScript#getConfigFields()}.
 *
 * @param key          the configuration key (used for lookup in {@link ScriptConfig})
 * @param label        the human-readable label shown in UIs
 * @param kind         the value kind, drives editor rendering and parsing
 * @param defaultValue the default value, typed to match {@code kind}
 * @param choices      enumerated options for {@link Kind#CHOICE}; empty otherwise
 */
public record ConfigField(String key, String label, Kind kind, Object defaultValue, List<String> choices) {

    public ConfigField {
        choices = List.copyOf(choices);
    }

    public enum Kind {
        INT, STRING, BOOLEAN, CHOICE, ITEM_ID
    }

    public static ConfigField intField(String key, String label, int defaultValue) {
        return new ConfigField(key, label, Kind.INT, defaultValue, List.of());
    }

    public static ConfigField stringField(String key, String label, String defaultValue) {
        return new ConfigField(key, label, Kind.STRING, defaultValue, List.of());
    }

    public static ConfigField boolField(String key, String label, boolean defaultValue) {
        return new ConfigField(key, label, Kind.BOOLEAN, defaultValue, List.of());
    }

    public static ConfigField choiceField(String key, String label, List<String> choices, String defaultValue) {
        return new ConfigField(key, label, Kind.CHOICE, defaultValue, choices);
    }

    public static ConfigField itemIdField(String key, String label, int defaultValue) {
        return new ConfigField(key, label, Kind.ITEM_ID, defaultValue, List.of());
    }
}
