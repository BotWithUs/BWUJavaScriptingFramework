package com.botwithus.bot.api.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * Immutable record describing a single script failure: the lifecycle phase in
 * which the throwable surfaced, the loop iteration index at the time, the
 * wall-clock {@link Instant} the runner observed it, and the throwable itself.
 *
 * <p>The {@link #iteration} field is the loop counter at crash time. For
 * crashes outside {@link Phase#ON_LOOP} the value is the most recent loop
 * count observed by the profiler — useful for ordering but not load-bearing.</p>
 */
public record LastCrash(Phase phase, long iteration, Instant when, Throwable cause) {

    /**
     * Renders the throwable as the conventional multi-line stack trace string.
     * Suitable for direct display in GUI surfaces (collapsing-header bodies,
     * log lines, notification tooltips).
     */
    public String stackTrace() {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
