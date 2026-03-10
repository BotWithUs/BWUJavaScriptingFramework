package com.botwithus.bot.api.script;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Schedules scripts to run at a future time or on a recurring interval.
 *
 * <p>Example usage from a manager script:
 * <pre>{@code
 * ScriptScheduler scheduler = ctx.getScriptManager().getScheduler();
 *
 * // Run "Woodcutter" once after 10 minutes
 * String id = scheduler.runAfter("Woodcutter", Duration.ofMinutes(10));
 *
 * // Run "Fisher" every 2 hours
 * String id2 = scheduler.runEvery("Fisher", Duration.ofHours(2));
 *
 * // Run "Miner" at a specific time
 * String id3 = scheduler.runAt("Miner", Instant.parse("2026-03-09T14:00:00Z"));
 *
 * // Run "Crafter" every 30 min, stop it after 5 min each time
 * String id4 = scheduler.runEvery("Crafter", Duration.ofMinutes(30), Duration.ofMinutes(5));
 *
 * // Cancel
 * scheduler.cancel(id);
 *
 * // See what's scheduled
 * scheduler.listScheduled().forEach(s -> System.out.println(s.scriptName() + " → " + s.nextRun()));
 * }</pre>
 */
public interface ScriptScheduler {

    /**
     * Starts a script after the given delay.
     *
     * @param scriptName name of the script to start
     * @param delay      how long to wait before starting
     * @return a unique schedule ID for cancellation
     */
    String runAfter(String scriptName, Duration delay);

    /**
     * Starts a script after the given delay with configuration.
     *
     * @param scriptName name of the script to start
     * @param delay      how long to wait before starting
     * @param config     configuration values to apply
     * @return a unique schedule ID for cancellation
     */
    String runAfter(String scriptName, Duration delay, Map<String, Object> config);

    /**
     * Starts a script at a specific time.
     *
     * @param scriptName name of the script to start
     * @param at         when to start it
     * @return a unique schedule ID for cancellation
     */
    String runAt(String scriptName, Instant at);

    /**
     * Repeats a script on a fixed interval. The script is started, and when it
     * stops (or is stopped), it is restarted after {@code interval} elapses.
     *
     * @param scriptName name of the script to start
     * @param interval   time between starts
     * @return a unique schedule ID for cancellation
     */
    String runEvery(String scriptName, Duration interval);

    /**
     * Repeats a script on a fixed interval, automatically stopping it after
     * {@code maxDuration} each time.
     *
     * @param scriptName  name of the script to start
     * @param interval    time between starts
     * @param maxDuration maximum time the script runs per cycle before being stopped
     * @return a unique schedule ID for cancellation
     */
    String runEvery(String scriptName, Duration interval, Duration maxDuration);

    /**
     * Cancels a scheduled or repeating script.
     *
     * @param scheduleId the ID returned by a {@code run*} method
     * @return {@code true} if the schedule was found and cancelled
     */
    boolean cancel(String scheduleId);

    /**
     * Cancels all scheduled and repeating scripts.
     */
    void cancelAll();

    /**
     * Lists all active schedules.
     *
     * @return snapshot of currently scheduled entries
     */
    List<ScheduledEntry> listScheduled();

    /**
     * A snapshot of one scheduled entry.
     *
     * @param id          unique schedule ID
     * @param scriptName  the script to run
     * @param nextRun     when the next execution is planned
     * @param interval    repeat interval, or {@code null} for one-shot
     * @param maxDuration auto-stop duration, or {@code null} for unlimited
     */
    record ScheduledEntry(
            String id,
            String scriptName,
            Instant nextRun,
            Duration interval,
            Duration maxDuration
    ) {}
}
