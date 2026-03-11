package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the lifecycle of {@link ManagementScript} instances.
 * Analogous to {@link ScriptRuntime} but for connection-independent scripts.
 */
public class ManagementScriptRuntime {

    private final ManagementContext context;
    private final List<ManagementScriptRunner> runners = new CopyOnWriteArrayList<>();
    private Runnable onStateChange;

    public ManagementScriptRuntime(ManagementContext context) {
        this.context = context;
    }

    public void setOnStateChange(Runnable callback) {
        this.onStateChange = callback;
    }

    private void fireStateChange() {
        Runnable cb = this.onStateChange;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                System.err.println("[ManagementRuntime] State change callback error: " + e.getMessage());
            }
        }
    }

    /** Registers a management script without starting it. */
    public ManagementScriptRunner registerScript(ManagementScript script) {
        ManagementScriptRunner runner = new ManagementScriptRunner(script, context);
        runners.add(runner);
        return runner;
    }

    /** Registers and immediately starts a management script. */
    public void startScript(ManagementScript script) {
        ManagementScriptRunner runner = registerScript(script);
        runner.start();
        System.out.println("[ManagementRuntime] Started: " + runner.getScriptName());
        fireStateChange();
    }

    /** Finds a runner by script name (case-insensitive). */
    public ManagementScriptRunner findRunner(String name) {
        for (ManagementScriptRunner runner : runners) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        return null;
    }

    /** Stops a running management script by name. */
    public boolean stopScript(String name) {
        ManagementScriptRunner runner = findRunner(name);
        if (runner != null && runner.isRunning()) {
            runner.stop();
            System.out.println("[ManagementRuntime] Stopped: " + runner.getScriptName());
            fireStateChange();
            return true;
        }
        return false;
    }

    /** Removes a stopped script from the registry. */
    public boolean removeScript(String name) {
        ManagementScriptRunner runner = findRunner(name);
        if (runner != null && !runner.isRunning()) {
            runners.remove(runner);
            return true;
        }
        return false;
    }

    /** Stops all management scripts and clears the registry. */
    public void stopAll() {
        for (ManagementScriptRunner runner : runners) {
            runner.stop();
            System.out.println("[ManagementRuntime] Stopped: " + runner.getScriptName());
        }
        runners.clear();
        fireStateChange();
    }

    /** Returns all registered runners. */
    public List<ManagementScriptRunner> getRunners() {
        return List.copyOf(runners);
    }
}
