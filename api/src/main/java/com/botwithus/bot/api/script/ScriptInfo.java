package com.botwithus.bot.api.script;

import com.botwithus.bot.api.ScriptCategory;

/**
 * Read-only snapshot of a script's metadata and current state.
 *
 * @param name        display name from {@link com.botwithus.bot.api.ScriptManifest}
 * @param version     version string, or {@code "?"} if unknown
 * @param author      author, or {@code "unknown"} if not declared
 * @param description description, or empty string
 * @param category    script category, or {@link ScriptCategory#UNCATEGORIZED}
 * @param running     whether the script is currently executing
 * @param className   fully qualified class name of the script implementation
 */
public record ScriptInfo(
        String name,
        String version,
        String author,
        String description,
        ScriptCategory category,
        boolean running,
        String className
) {}
