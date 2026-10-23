package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementScript;

import java.util.List;

/**
 * Aggregate result of a load pass over {@code scripts/management/}: one
 * {@link ManagementLoadResult} per script or failed JAR. The management
 * counterpart of {@link LoadReport}.
 */
public record ManagementLoadReport(List<ManagementLoadResult> results) {

    /** Empty report: the folder is missing or holds no JARs. */
    public static final ManagementLoadReport EMPTY = new ManagementLoadReport(List.of());

    public ManagementLoadReport {
        results = List.copyOf(results);
    }

    /** Every management script that loaded, in encounter order. */
    public List<ManagementScript> scripts() {
        return results.stream()
                .flatMap(r -> r.script().stream())
                .toList();
    }

    /** Every JAR that failed, in encounter order. */
    public List<ManagementLoadResult> failures() {
        return results.stream()
                .filter(r -> r.error().isPresent())
                .toList();
    }
}
