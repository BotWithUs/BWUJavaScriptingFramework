package com.botwithus.bot.api.script;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;

import java.util.List;

/**
 * Service Provider Interface for independent management scripts.
 *
 * <p>Management scripts are <b>not tied to any single client</b>. They run
 * independently and coordinate across clients using the
 * {@link ClientOrchestrator} and {@link com.botwithus.bot.api.ClientProvider}.
 *
 * <p>Use these for orchestration tasks such as:
 * <ul>
 *   <li>Starting/stopping scripts across groups of clients</li>
 *   <li>Monitoring and reacting to cross-client state</li>
 *   <li>Rotating scripts based on schedules or conditions</li>
 *   <li>Coordinating multi-account workflows</li>
 * </ul>
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * Each script JAR must declare
 * {@code provides com.botwithus.bot.api.script.ManagementScript with <ClassName>}
 * in its {@code module-info.java}.
 *
 * <p>Annotate implementations with {@link ScriptManifest} to declare metadata.
 *
 * <h3>Quick start</h3>
 * <pre>{@code
 * @ScriptManifest(name = "GroupRotator", version = "1.0",
 *         description = "Rotates scripts across client groups")
 * public class GroupRotator implements ManagementScript {
 *
 *     private ClientOrchestrator orchestrator;
 *
 *     @Override
 *     public void onStart(ManagementContext ctx) {
 *         orchestrator = ctx.getOrchestrator();
 *         orchestrator.createGroup("skillers", "Skilling accounts");
 *     }
 *
 *     @Override
 *     public int onLoop() {
 *         orchestrator.startScriptOnGroup("skillers", "Woodcutter");
 *         return 60_000; // check every minute
 *     }
 *
 *     @Override
 *     public void onStop() {
 *         orchestrator.stopAllScriptsOnAll();
 *     }
 * }
 * }</pre>
 *
 * @see ManagementContext
 * @see ClientOrchestrator
 * @see ScriptManifest
 */
public interface ManagementScript {

    /**
     * Called once when the script is started.
     *
     * @param ctx the management context providing cross-client access
     */
    void onStart(ManagementContext ctx);

    /**
     * Called repeatedly while the script is running.
     *
     * @return delay in milliseconds before the next loop, or {@code -1} to stop
     */
    int onLoop();

    /**
     * Called once when the script is stopped.
     */
    void onStop();

    /**
     * Returns the configurable fields this script exposes.
     */
    default List<ConfigField> getConfigFields() {
        return List.of();
    }

    /**
     * Called when configuration is updated.
     */
    default void onConfigUpdate(ScriptConfig config) {
    }

    /**
     * Returns a custom UI for this script, rendered in the Script UI panel.
     */
    default ScriptUI getUI() {
        return null;
    }
}
