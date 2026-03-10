package com.botwithus.bot.api.script;

/**
 * Read-only snapshot of a script's metadata and current state.
 *
 * @param name        display name from {@link com.botwithus.bot.api.ScriptManifest}
 * @param version     version string, or {@code "?"} if unknown
 * @param author      author, or {@code "unknown"} if not declared
 * @param description description, or empty string
 * @param running     whether the script is currently executing
 * @param className   fully qualified class name of the script implementation
 */
public record ScriptInfo(
        String name,
        String version,
        String author,
        String description,
        boolean running,
        String className
) {}
