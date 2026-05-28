package com.botwithus.bot.cli.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured result from a command execution that UI panels can consume.
 */
public record CommandResult(boolean success, String message, Map<Key<?>, Object> data) {

    /**
     * Typed key into a result payload. The type parameter binds the key to the type of the
     * value stored under it, which is what makes {@link #get(Key)} type-safe.
     */
    public record Key<T>(String name) {}

    public static CommandResult ok() {
        return new CommandResult(true, null, Map.of());
    }

    public static CommandResult ok(String message) {
        return new CommandResult(true, message, Map.of());
    }

    public static <T> CommandResult ok(String message, Key<T> key, T value) {
        Map<Key<?>, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return new CommandResult(true, message, Map.copyOf(data));
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message, Map.of());
    }

    public <T> T get(Key<T> key) {
        // Type-safe by construction: a value is only ever stored under a Key<T> whose type
        // parameter matches it (see ok(message, key, value)), so this narrowing always holds.
        @SuppressWarnings("unchecked")
        T value = (T) data.get(key);
        return value;
    }
}
