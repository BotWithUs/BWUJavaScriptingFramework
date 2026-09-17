package com.botwithus.bot.api.config;

import java.util.List;

/**
 * Describes a single configurable parameter that a script exposes.
 * Scripts return a list of these from {@link com.botwithus.bot.api.BotScript#getConfigFields()}.
 *
 * <p>A {@code ConfigField} is a sealed hierarchy of typed variants. Scripts construct
 * them through the static factory methods ({@link #intField}, {@link #stringField},
 * {@link #boolField}, {@link #choiceField}, {@link #itemIdField}); consumers dispatch
 * over the variants with an exhaustive {@code switch} pattern match.
 */
public sealed interface ConfigField
        permits ConfigField.IntField, ConfigField.StringField, ConfigField.BoolField,
        ConfigField.ChoiceField, ConfigField.ItemIdField {

    /** @return the configuration key (used for lookup in {@link ScriptConfig}) */
    String key();

    /** @return the human-readable label shown in UIs */
    String label();

    /**
     * @return the string form of this field's default value, in the same format
     * the persistence layer stores on disk.
     */
    default String defaultAsString() {
        return switch (this) {
            case IntField f -> String.valueOf(f.value());
            case StringField f -> String.valueOf(f.value());
            case BoolField f -> String.valueOf(f.value());
            case ChoiceField f -> f.value();
            case ItemIdField f -> String.valueOf(f.value());
        };
    }

    /** An integer-valued field. */
    record IntField(String key, String label, int value) implements ConfigField {}

    /** A free-text string-valued field. */
    record StringField(String key, String label, String value) implements ConfigField {}

    /** A boolean-valued field rendered as a checkbox. */
    record BoolField(String key, String label, boolean value) implements ConfigField {}

    /** A field whose value is one of an enumerated set of choices. */
    record ChoiceField(String key, String label, List<String> choices, String value)
            implements ConfigField {
        public ChoiceField {
            choices = List.copyOf(choices);
        }
    }

    /** An integer-valued field representing an item id. */
    record ItemIdField(String key, String label, int value) implements ConfigField {}

    static ConfigField intField(String key, String label, int defaultValue) {
        return new IntField(key, label, defaultValue);
    }

    static ConfigField stringField(String key, String label, String defaultValue) {
        return new StringField(key, label, defaultValue);
    }

    static ConfigField boolField(String key, String label, boolean defaultValue) {
        return new BoolField(key, label, defaultValue);
    }

    static ConfigField choiceField(String key, String label, List<String> choices, String defaultValue) {
        return new ChoiceField(key, label, choices, defaultValue);
    }

    static ConfigField itemIdField(String key, String label, int defaultValue) {
        return new ItemIdField(key, label, defaultValue);
    }
}
