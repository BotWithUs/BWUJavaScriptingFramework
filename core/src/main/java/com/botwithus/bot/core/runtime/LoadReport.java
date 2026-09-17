package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import java.util.List;

/**
 * Aggregate result of a load pass over a scripts directory: one
 * {@link ScriptLoadResult} per JAR encountered. {@link #scripts()} unwraps
 * the successful loads; {@link #failures()} extracts only the failures so
 * the GUI can render a single "Failed to load" card per JAR.
 */
public record LoadReport(List<ScriptLoadResult> results) {

    public LoadReport {
        results = List.copyOf(results);
    }

    /** All successfully-loaded scripts, in encounter order. */
    public List<BotScript> scripts() {
        return results.stream()
                .flatMap(r -> r.script().stream())
                .toList();
    }

    /** All failed JARs, in encounter order. */
    public List<ScriptLoadResult> failures() {
        return results.stream()
                .filter(r -> r.error().isPresent())
                .toList();
    }

    /** Empty report — used when the scripts dir is missing or contains no JARs. */
    public static final LoadReport EMPTY = new LoadReport(List.of());
}
