package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ScriptInfo;
import com.botwithus.bot.api.script.ScriptManager;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.core.runtime.LocalScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.List;
import java.util.Map;

/**
 * Core implementation of {@link ScriptManager} that delegates to
 * {@link ScriptRuntime} for lifecycle and {@link LocalScriptLoader} for discovery.
 */
public class ScriptManagerImpl implements ScriptManager {

    private static final Logger log = LoggerFactory.getLogger(ScriptManagerImpl.class);
    private final ScriptRuntime runtime;
    private final ScriptSchedulerImpl scheduler;

    public ScriptManagerImpl(ScriptRuntime runtime) {
        this.runtime = runtime;
        this.scheduler = new ScriptSchedulerImpl(this);
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    @Override
    public List<ScriptInfo> listAll() {
        return runtime.getRunners().stream()
                .map(this::toInfo)
                .toList();
    }

    @Override
    public List<ScriptInfo> listRunning() {
        return runtime.getRunners().stream()
                .filter(ScriptRunner::isRunning)
                .map(this::toInfo)
                .toList();
    }

    @Override
    public ScriptInfo getInfo(String name) {
        ScriptRunner runner = runtime.findRunner(name);
        return runner != null ? toInfo(runner) : null;
    }

    @Override
    public boolean isRunning(String name) {
        ScriptRunner runner = runtime.findRunner(name);
        return runner != null && runner.isRunning();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public boolean start(String name) {
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) return false;
        if (runner.isRunning()) {
            throw new IllegalStateException("Script already running: " + name);
        }
        runner.start();
        log.info("Started: {}", runner.getScriptName());
        return true;
    }

    @Override
    public boolean start(String name, Map<String, Object> config) {
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) return false;
        if (runner.isRunning()) {
            throw new IllegalStateException("Script already running: " + name);
        }

        // Apply config before starting
        if (config != null && !config.isEmpty()) {
            var fields = runner.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                // Convert Object values to Strings for ScriptConfig
                Map<String, String> stringConfig = new java.util.LinkedHashMap<>();
                config.forEach((k, v) -> stringConfig.put(k, String.valueOf(v)));
                runner.applyConfig(new ScriptConfig(stringConfig));
            }
        }

        runner.start();
        log.info("Started: {} with {} config values", runner.getScriptName(),
                config != null ? config.size() : 0);
        return true;
    }

    @Override
    public boolean stop(String name) {
        boolean stopped = runtime.stopScript(name);
        if (stopped) {
            log.info("Stopped: {}", name);
        }
        return stopped;
    }

    @Override
    public boolean restart(String name) {
        ScriptRunner runner = runtime.findRunner(name);
        if (runner == null) return false;

        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        log.info("Restarted: {}", runner.getScriptName());
        return true;
    }

    @Override
    public void stopAll() {
        runtime.stopAll();
        log.info("Stopped all scripts");
    }

    // ── Loading ────────────────────────────────────────────────────────────────

    @Override
    public List<ScriptInfo> reloadLocal() {
        // Stop all running scripts first
        runtime.stopAll();

        // Reload from disk
        List<BotScript> scripts = LocalScriptLoader.loadScripts();
        log.info("Reloaded {} script(s) from local", scripts.size());

        // Register without starting
        for (BotScript script : scripts) {
            runtime.registerScript(script);
        }

        return listAll();
    }

    // ── Scheduling ─────────────────────────────────────────────────────────────

    @Override
    public ScriptScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Shuts down the scheduler. Called during application shutdown.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private ScriptInfo toInfo(ScriptRunner runner) {
        ScriptManifest m = runner.getManifest();
        return new ScriptInfo(
                runner.getScriptName(),
                m != null ? m.version() : "?",
                m != null && !m.author().isEmpty() ? m.author() : "unknown",
                m != null && !m.description().isEmpty() ? m.description() : "",
                m != null ? m.category() : ScriptCategory.UNCATEGORIZED,
                runner.isRunning(),
                runner.getScript().getClass().getName()
        );
    }
}
