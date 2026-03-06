package com.botwithus.bot.api.config;

import java.util.List;

/**
 * Describes a single configurable parameter that a script exposes.
 * Scripts return a list of these from {@link com.botwithus.bot.api.BotScript#getConfigFields()}.
 */
public final class ConfigField {

    /** The type of value this field holds. */
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

    /**
     * Creates an integer config field.
     *
     * @param key          the unique key for persistence
     * @param label        the display label
     * @param defaultValue the default value
     * @return a new ConfigField
     */
    public static ConfigField intField(String key, String label, int defaultValue) {
        return new ConfigField(key, label, Kind.INT, defaultValue, List.of());
    }

    /**
     * Creates a string config field.
     *
     * @param key          the unique key for persistence
     * @param label        the display label
     * @param defaultValue the default value
     * @return a new ConfigField
     */
    public static ConfigField stringField(String key, String label, String defaultValue) {
        return new ConfigField(key, label, Kind.STRING, defaultValue, List.of());
    }

    /**
     * Creates a boolean config field.
     *
     * @param key          the unique key for persistence
     * @param label        the display label
     * @param defaultValue the default value
     * @return a new ConfigField
     */
    public static ConfigField boolField(String key, String label, boolean defaultValue) {
        return new ConfigField(key, label, Kind.BOOLEAN, defaultValue, List.of());
    }

    /**
     * Creates a choice (dropdown) config field.
     *
     * @param key          the unique key for persistence
     * @param label        the display label
     * @param choices      the available options
     * @param defaultValue the default selected option
     * @return a new ConfigField
     */
    public static ConfigField choiceField(String key, String label, List<String> choices, String defaultValue) {
        return new ConfigField(key, label, Kind.CHOICE, defaultValue, List.copyOf(choices));
    }

    /**
     * Creates an item ID config field.
     *
     * @param key          the unique key for persistence
     * @param label        the display label
     * @param defaultValue the default item ID
     * @return a new ConfigField
     */
    public static ConfigField itemIdField(String key, String label, int defaultValue) {
        return new ConfigField(key, label, Kind.ITEM_ID, defaultValue, List.of());
    }
}
