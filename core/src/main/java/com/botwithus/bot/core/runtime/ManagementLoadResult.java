package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementScript;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Outcome of loading one management-script JAR: the management counterpart of
 * {@link ScriptLoadResult}. {@link #jar()} and {@link #lastModified()} describe
 * the JAR in {@code scripts/management/}, never the staged copy.
 */
public record ManagementLoadResult(Path jar,
                                   Optional<ManagementScript> script,
                                   Optional<Throwable> error,
                                   Optional<Instant> lastModified) implements JarLoadOutcome {

    @Override
    public boolean isSuccess() {
        return script.isPresent();
    }

    @Override
    public Optional<String> scriptName() {
        return script.map(JarLoadOutcome::nameOf);
    }

    /** A loaded management script; reads {@code jar}'s last-modified time now. */
    public static ManagementLoadResult success(Path jar, ManagementScript script) {
        return new ManagementLoadResult(jar, Optional.of(script), Optional.empty(),
                JarLoadOutcome.readLastModified(jar));
    }

    /** A management JAR that did not load; reads {@code jar}'s last-modified time now. */
    public static ManagementLoadResult failure(Path jar, Throwable cause) {
        return new ManagementLoadResult(jar, Optional.empty(), Optional.of(cause),
                JarLoadOutcome.readLastModified(jar));
    }
}
