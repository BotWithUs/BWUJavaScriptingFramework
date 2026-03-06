package com.botwithus.bot.api.config;

import java.util.List;

/**
 * Describes a single configurable parameter that a script exposes.
 * Scripts return a list of these from {@link com.botwithus.bot.api.BotScript#getConfigFields()}.
 */
public final class ConfigField {

    public enum Kind {
        INT, STRING, BOOLEAN, CHOICE, ITEM_ID
    }

    private final String key;
    private final String label;
    private final Kind kind;
    private final Object defaultValue;
    private final List<String> choices;

    private ConfigField(String key, String label, Kind kind, Object defaultValue, List<String> choices) {
        this.key = key;
        this.label = label;
        this.kind = kind;
        this.defaultValue = defaultValue;
        this.choices = choices;
    }

    public String key() { return key; }
    public String label() { return label; }
    public Kind kind() { return kind; }
    public Object defaultValue() { return defaultValue; }
    public List<String> choices() { return choices; }

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
        return new ConfigField(key, label, Kind.CHOICE, defaultValue, List.copyOf(choices));
    }

    public static ConfigField itemIdField(String key, String label, int defaultValue) {
        return new ConfigField(key, label, Kind.ITEM_ID, defaultValue, List.of());
    }
}
