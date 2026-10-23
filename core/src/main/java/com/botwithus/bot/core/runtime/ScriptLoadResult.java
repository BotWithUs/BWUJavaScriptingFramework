package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outcome of loading one script JAR. Either {@link #script()} is present and
 * {@link #error()} is empty (success), or {@link #error()} carries the cause
 * and {@link #script()} is empty (failure).
 *
 * <p>Both arms can carry {@link #diagnostics()} — non-fatal observations
 * (e.g. a module that declares {@code provides BotScript} but contains zero
 * implementations is technically a success-with-warnings).</p>
 *
 * <p>{@link #jar()} and {@link #lastModified()} both describe the JAR in the
 * scripts folder, never the staged copy it was loaded from.</p>
 */
public record ScriptLoadResult(Path jar,
                               Optional<BotScript> script,
                               Optional<Throwable> error,
                               List<String> diagnostics,
                               Optional<Instant> lastModified) implements JarLoadOutcome {

    public ScriptLoadResult {
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

    /** A loaded script; reads {@code jar}'s last-modified time now. */
    public static ScriptLoadResult success(Path jar, BotScript script, List<String> diagnostics) {
        return new ScriptLoadResult(jar, Optional.of(script), Optional.empty(), diagnostics,
                JarLoadOutcome.readLastModified(jar));
    }

    /** A JAR that did not load; reads {@code jar}'s last-modified time now. */
    public static ScriptLoadResult failure(Path jar, Throwable cause, List<String> diagnostics) {
        return new ScriptLoadResult(jar, Optional.empty(), Optional.of(cause), diagnostics,
                JarLoadOutcome.readLastModified(jar));
    }
}
