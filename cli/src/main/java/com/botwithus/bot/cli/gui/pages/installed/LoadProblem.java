package com.botwithus.bot.cli.gui.pages.installed;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of the page's "Failed to load" section: a JAR that did not load, or
 * an older copy of a script that a newer JAR shadows.
 *
 * @param message    one line: the error, or which JAR runs instead
 * @param stackTrace the full trace for a failure; empty for a duplicate
 * @param hint       what to do about it, when the page can tell
 */
public record LoadProblem(Kind kind, Path jar, String message, String stackTrace, Optional<String> hint) {

    /** A failure is red; a shadowed duplicate still loaded, so it is amber. */
    public enum Kind { FAILED, OLDER_DUPLICATE }

    public LoadProblem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(stackTrace, "stackTrace");
        Objects.requireNonNull(hint, "hint");
    }

    public String jarName() {
        return jar.getFileName().toString();
    }

    public boolean hasStackTrace() {
        return !stackTrace.isEmpty();
    }
}
