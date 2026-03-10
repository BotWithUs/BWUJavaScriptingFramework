package com.botwithus.bot.api.script;

import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of other scripts from within a script.
 *
 * <p>Obtain via {@link com.botwithus.bot.api.ScriptContext#getScriptManager()}.
 *
 * <h3>Quick start</h3>
 * <pre>{@code
 * ScriptManager mgr = ctx.getScriptManager();
 *
 * // See what's available
 * mgr.listAll().forEach(s ->
 *     System.out.println(s.name() + " [" + (s.running() ? "RUNNING" : "STOPPED") + "]"));
 *
 * // Start a script
 * mgr.start("Woodcutter");
 *
 * // Start with config
 * mgr.start("Fisher", Map.of("location", "Barbarian Village", "dropFish", true));
 *
 * // Stop it later
 * mgr.stop("Woodcutter");
 *
 * // Reload scripts from disk (picks up new JARs)
 * List<ScriptInfo> fresh = mgr.reloadLocal();
 *
 * // Schedule scripts
 * ScriptScheduler scheduler = mgr.getScheduler();
 * scheduler.runAfter("Miner", Duration.ofMinutes(10));
 * scheduler.runEvery("Crafter", Duration.ofHours(1), Duration.ofMinutes(30));
 * }</pre>
 */
public interface ScriptManager {

    // ── Query ──────────────────────────────────────────────────────────────────

    /**
     * Returns info about all known scripts (running and stopped).
     */
    List<ScriptInfo> listAll();

    /**
     * Returns info about currently running scripts only.
     */
    List<ScriptInfo> listRunning();

    /**
     * Returns info for a single script by name, or {@code null} if not found.
     */
    ScriptInfo getInfo(String name);

    /**
     * Returns {@code true} if the named script is currently running.
     */
    boolean isRunning(String name);

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Starts a script by name. The script must already be loaded (discovered).
     *
     * @param name the script name (from {@link com.botwithus.bot.api.ScriptManifest})
     * @return {@code true} if the script was found and started
     * @throws IllegalStateException if the script is already running
     */
    boolean start(String name);

    /**
     * Starts a script with the given configuration values.
     *
     * @param name   the script name
     * @param config key-value pairs matching the script's config field names
     * @return {@code true} if the script was found and started
     * @throws IllegalStateException if the script is already running
     */
    boolean start(String name, Map<String, Object> config);

    /**
     * Stops a running script.
     *
     * @param name the script name
     * @return {@code true} if the script was found and stopped
     */
    boolean stop(String name);

    /**
     * Stops and restarts a script. If the script is not running, just starts it.
     *
     * @param name the script name
     * @return {@code true} if the script was found
     */
    boolean restart(String name);

    /**
     * Stops all running scripts.
     */
    void stopAll();

    // ── Loading ────────────────────────────────────────────────────────────────

    /**
     * Reloads scripts from the local {@code scripts/} directory.
     * Newly discovered scripts are registered but not started.
     *
     * @return list of all scripts after reload
     */
    List<ScriptInfo> reloadLocal();

    // ── Scheduling ─────────────────────────────────────────────────────────────

    /**
     * Returns the scheduler for running scripts on a timer or interval.
     */
    ScriptScheduler getScheduler();
}
