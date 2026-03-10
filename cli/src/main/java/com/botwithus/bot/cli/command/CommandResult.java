package com.botwithus.bot.cli.command;

import java.util.Map;

/**
 * Structured result from a command execution that UI panels can consume.
 */
public record CommandResult(boolean success, String message, Map<String, Object> data) {

    public static CommandResult ok() {
        return new CommandResult(true, null, Map.of());
    }

    public static CommandResult ok(String message) {
        return new CommandResult(true, message, Map.of());
    }

    public static CommandResult ok(String message, Map<String, Object> data) {
        return new CommandResult(true, message, data);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message, Map.of());
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }
}
