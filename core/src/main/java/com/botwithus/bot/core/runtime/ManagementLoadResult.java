package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementScript;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outcome of loading one management-script JAR: the management counterpart of
 * {@link ScriptLoadResult}. {@link #jar()} and {@link #lastModified()} describe
 * the JAR in {@code scripts/management/}, never the staged copy.
 *
 * <p>Both arms can carry {@link #diagnostics()}: non-fatal observations about
 * the JAR, such as a module that reaches into host internals.</p>
 */
public record ManagementLoadResult(Path jar,
                                   Optional<ManagementScript> script,
                                   Optional<Throwable> error,
                                   List<String> diagnostics,
                                   Optional<Instant> lastModified) implements JarLoadOutcome {

    public ManagementLoadResult {
        diagnostics = List.copyOf(diagnostics);
    }

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
        return success(jar, script, List.of());
    }

    /** A loaded management script with diagnostics; reads {@code jar}'s last-modified time now. */
    public static ManagementLoadResult success(Path jar, ManagementScript script, List<String> diagnostics) {
        return new ManagementLoadResult(jar, Optional.of(script), Optional.empty(), diagnostics,
                JarLoadOutcome.readLastModified(jar));
    }

    /** A management JAR that did not load; reads {@code jar}'s last-modified time now. */
    public static ManagementLoadResult failure(Path jar, Throwable cause) {
        return failure(jar, cause, List.of());
    }

    /** A management JAR that did not load, with diagnostics; reads {@code jar}'s last-modified time now. */
    public static ManagementLoadResult failure(Path jar, Throwable cause, List<String> diagnostics) {
        return new ManagementLoadResult(jar, Optional.empty(), Optional.of(cause), diagnostics,
                JarLoadOutcome.readLastModified(jar));
    }
}
