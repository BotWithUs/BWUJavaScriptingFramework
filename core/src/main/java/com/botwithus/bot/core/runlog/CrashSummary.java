package com.botwithus.bot.core.runlog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The last crash of a script's run, as a report needs it (the {@code crash}
 * object of spec §4). Every string is already redacted.
 *
 * @param runId       the run the crash belongs to
 * @param logFile     that run's log file; empty if it could not be created
 * @param phase       lifecycle phase, spelled as the spec spells it
 * @param iteration   loop count when it happened
 * @param at          when the runner saw it
 * @param exception   {@code class: message}, first line only
 * @param topFrame    first frame inside script code, or {@code unknown}
 * @param stack       the full trace, one element per line, two-space indented
 * @param breadcrumbs the run's last events, oldest first
 */
public record CrashSummary(
        String runId,
        Optional<Path> logFile,
        CrashPhase phase,
        long iteration,
        Instant at,
        String exception,
        String topFrame,
        String stack,
        List<String> breadcrumbs) {

    public CrashSummary {
        breadcrumbs = List.copyOf(breadcrumbs);
    }
}
