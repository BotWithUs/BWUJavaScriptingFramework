package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * What one JAR yielded in a load pass, whichever loader read it. The common
 * view {@link LoadIssues} keeps its failed-load list from, so bot scripts and
 * management scripts are tracked by one rule.
 */
public interface JarLoadOutcome {

    /**
     * The JAR as the scripter built it, in the scripts folder. Never the
     * private staged copy the loader actually opened.
     */
    Path jar();

    /** Why the JAR did not load; empty on success. */
    Optional<Throwable> error();

    /**
     * When {@link #jar()} was last modified, read from that file while it was
     * loaded. Empty when the file could not be read (it was deleted or
     * replaced mid-load).
     */
    Optional<Instant> lastModified();

    /**
     * The name a runtime registers the loaded script under: its
     * {@link ScriptManifest#name()}, else its class's simple name. Empty for a
     * failure.
     */
    Optional<String> scriptName();

    /** True when the JAR yielded a script. */
    default boolean isSuccess() {
        return error().isEmpty();
    }

    /**
     * The last-modified time of {@code jar}, or empty when it cannot be read.
     * Never throws: a JAR that vanished is a normal event for a folder a
     * scripter rebuilds into.
     */
    static Optional<Instant> readLastModified(Path jar) {
        try {
            return Optional.of(Files.getLastModifiedTime(jar).toInstant());
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    /**
     * The name a runtime registers {@code script} under — the same rule
     * {@link ScriptRuntime} and {@link ManagementScriptRuntime} apply, so a
     * duplicate reported here is a duplicate there.
     */
    static String nameOf(Object script) {
        ScriptManifest manifest = script.getClass().getAnnotation(ScriptManifest.class);
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }
}
