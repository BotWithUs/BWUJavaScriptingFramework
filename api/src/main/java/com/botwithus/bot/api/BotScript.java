package com.botwithus.bot.api;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;

import java.util.List;

/**
 * Service Provider Interface for bot scripts.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * Each script follows a lifecycle of {@link #onStart} &rarr; {@link #onLoop} &rarr; {@link #onStop}.</p>
 *
 * <p>Annotate implementations with {@link ScriptManifest} to declare metadata.</p>
 *
 * @see ScriptManifest
 * @see ScriptContext
 */
public interface BotScript {

    /**
     * Called once when the script is started.
     * Use this to initialize state and cache references from the context.
     *
     * @param ctx the script context providing access to the {@link GameAPI} and event bus
     */
    void onStart(ScriptContext ctx);

    /**
     * Called repeatedly while the script is running.
     * The returned value controls the delay before the next invocation.
     *
     * @return delay in milliseconds before the next loop iteration, or {@code -1} to stop the script
     */
    int onLoop();

    /**
     * Called once when the script is stopped, either by returning {@code -1} from
     * {@link #onLoop()} or by an external stop request. Use this to clean up resources.
     */
    void onStop();

    /**
     * Returns the configurable fields this script exposes.
     * Override this to declare user-editable parameters.
     * The default implementation returns an empty list (no config).
     *
     * @return a list of config field descriptors
     */
    default List<ConfigField> getConfigFields() {
        return List.of();
    }

    /**
     * Called when the script's configuration is updated (at startup from persisted
     * values, or at runtime from the config UI).
     * The default implementation does nothing.
     *
     * @param config the new configuration snapshot
     */
    default void onConfigUpdate(ScriptConfig config) {
    }

    /**
     * Returns a custom UI for this script, rendered in the Script UI panel.
     * Override this to provide interactive widgets, status displays, or controls
     * beyond what {@link #getConfigFields()} offers.
     *
     * <p>The returned {@link ScriptUI} is called every frame on the UI thread.
     * Return {@code null} (the default) if this script has no custom UI.</p>
     *
     * @return a ScriptUI implementation, or null
     */
    default ScriptUI getUI() {
        return null;
    }
}
