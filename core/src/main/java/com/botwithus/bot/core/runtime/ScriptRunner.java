package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.core.blueprint.execution.BlueprintBotScript;
import com.botwithus.bot.core.config.ScriptConfigStore;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a single BotScript on its own virtual thread.
 * Lifecycle: onStart -> loop(onLoop + sleep) -> onStop
 */
public class ScriptRunner implements Runnable {

    private final BotScript script;
    private final ScriptContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private Thread thread;
    private String connectionName;

    public ScriptRunner(BotScript script, ScriptContext context) {
        this.script = script;
        this.context = context;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            String name = getScriptName();
            this.thread = Thread.ofVirtual().name("script-" + name).start(this);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public BotScript getScript() {
        return script;
    }

    public ScriptManifest getManifest() {
        return script.getClass().getAnnotation(ScriptManifest.class);
    }

    public String getScriptName() {
        if (script instanceof BlueprintBotScript bp) {
            return bp.getMetadata().name();
        }
        ScriptManifest manifest = getManifest();
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public List<ConfigField> getConfigFields() {
        return script.getConfigFields();
    }

    /**
     * Returns the current config snapshot, or {@code null} if not yet loaded.
     */
    public ScriptConfig getCurrentConfig() {
        return currentConfig.get();
    }

    /**
     * Applies a new configuration from the UI thread. Persists and notifies the script.
     */
    public void applyConfig(ScriptConfig config) {
        currentConfig.set(config);
        ScriptConfigStore.save(getScriptName(), config);
        try {
            script.onConfigUpdate(config);
        } catch (Exception e) {
            System.err.println("[ScriptRunner] Error in onConfigUpdate for " + getScriptName() + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        if (connectionName != null) {
            ConnectionContext.set(connectionName);
        }
        try {
            script.onStart(context);

            // Load persisted config after onStart
            List<ConfigField> fields = script.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(getScriptName(), fields);
                currentConfig.set(config);
                script.onConfigUpdate(config);
            }
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int delay = script.onLoop();
                if (delay < 0) break;
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[ScriptRunner] Script error in " + getScriptName() + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            running.set(false);
            try {
                script.onStop();
            } catch (Exception e) {
                System.err.println("[ScriptRunner] Error in onStop for " + getScriptName() + ": " + e.getMessage());
            }
            ConnectionContext.clear();
        }
    }
}
