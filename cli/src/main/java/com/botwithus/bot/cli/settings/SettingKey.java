package com.botwithus.bot.cli.settings;

import java.util.Objects;

/**
 * One typed entry in {@code config.properties}.
 *
 * <p>A key is pure metadata; values live in {@link HostSettings}. The catalogue of
 * keys the host knows is {@link SettingKeys#ALL}.</p>
 *
 * @param name         the property name in {@code config.properties}, e.g. {@code "scanIntervalMs"}
 * @param label        short human label, e.g. {@code "Scan every"}
 * @param description  one-line explanation for a settings row or {@code config show}
 * @param type         how the value is parsed, formatted and bounded
 * @param defaultValue the value when the file does not set one; must satisfy {@code type}
 * @param <T>          the Java type of the value
 */
public record SettingKey<T>(String name, String label, String description,
                            SettingType<T> type, T defaultValue) {

    public SettingKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("setting name is blank");
        }
        type.validate(defaultValue);
    }

    /**
     * Parses {@code raw} as a value of this key.
     *
     * @throws InvalidSettingException naming this key and the rule it broke
     */
    public T parse(String raw) {
        try {
            return type.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidSettingException(name, e.getMessage(), e);
        }
    }

    /**
     * Checks {@code value} against this key's bounds.
     *
     * @throws InvalidSettingException naming this key and the rule it broke
     */
    public void validate(T value) {
        try {
            type.validate(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSettingException(name, e.getMessage(), e);
        }
    }

    /** Text form of {@code value}, as written to {@code config.properties}. */
    public String format(T value) {
        return type.format(value);
    }

    /** Text form of {@link #defaultValue()}. */
    public String formattedDefault() {
        return type.format(defaultValue);
    }
}
