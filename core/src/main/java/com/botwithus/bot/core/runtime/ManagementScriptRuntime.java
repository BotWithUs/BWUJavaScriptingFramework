package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of {@link ManagementScript} instances.
 * Analogous to {@link ScriptRuntime} but for connection-independent scripts.
 */
public class ManagementScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ManagementScriptRuntime.class);
    /** How long to wait for a management-script thread to drain before abandoning it. */
    private static final long STOP_AWAIT_MS = 2000L;

    private final ManagementContext context;
    private final List<ManagementScriptRunner> runners = new CopyOnWriteArrayList<>();
    /** Runners whose threads refused to drain; kept visible rather than dropped. */
    private final List<ManagementScriptRunner> quarantined = new CopyOnWriteArrayList<>();
    private final LivenessWatchdog watchdog =
            new LivenessWatchdog("mgmt-script-watchdog", this::watchdogSubjects);
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
                log.error("State change callback error: {}", e.getMessage());
            }
        }
    }

    /** Registers a management script without starting it. */
    public ManagementScriptRunner registerScript(ManagementScript script) {
        ManagementScriptRunner runner = new ManagementScriptRunner(script, context);
        runner.setWatchdogArmer(this::ensureWatchdog);
        runners.add(runner);
        return runner;
    }

    /** Registers and immediately starts a management script. */
    public void startScript(ManagementScript script) {
        ManagementScriptRunner runner = registerScript(script);
        runner.start();
        log.info("Started: {}", runner.getScriptName());
        fireStateChange();
    }

    /**
     * Starts the watchdog on first use. Armed from
     * {@link ManagementScriptRunner#start()} so every start path is covered,
     * including the CLI and GUI which start a runner directly.
     */
    private void ensureWatchdog() {
        watchdog.arm();
    }

    /** Active runners followed by quarantined ones — everything the watchdog sweeps. */
    private Iterable<ManagementScriptRunner> watchdogSubjects() {
        return () -> Stream.concat(runners.stream(), quarantined.stream()).iterator();
    }

    /** One watchdog pass; time-parameterised so tests can drive it deterministically. */
    void sweep(long nowNanos) {
        watchdog.sweep(nowNanos);
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
            log.info("Stopped: {}", runner.getScriptName());
            fireStateChange();
            return true;
        }
        return false;
    }

    /** Removes a stopped script from the registry. */
    public boolean removeScript(String name) {
        ManagementScriptRunner runner = findRunner(name);
        if (runner != null && !runner.isRunning()) {
            runner.dispose();
            runners.remove(runner);
            return true;
        }
        return false;
    }

    /** Stops all management scripts and clears the registry. */
    public void stopAll() {
        for (ManagementScriptRunner runner : runners) {
            runner.dispose();
            log.info("Stopped: {}", runner.getScriptName());
        }
        // Drain threads before clearing references / any subsequent reload —
        // dispose() only interrupts cooperatively. Best-effort with a timeout.
        for (ManagementScriptRunner runner : runners) {
            if (!runner.awaitStop(STOP_AWAIT_MS)) {
                log.warn("Management script {} did not stop within {} ms; quarantining it",
                        runner.getScriptName(), STOP_AWAIT_MS);
                quarantined.add(runner);
            }
        }
        runners.clear();
        if (quarantined.isEmpty()) {
            watchdog.close();
        }
        fireStateChange();
    }

    /** Returns all known runners — active first, then quarantined zombies. */
    public List<ManagementScriptRunner> getRunners() {
        List<ManagementScriptRunner> all =
                new ArrayList<>(runners.size() + quarantined.size());
        all.addAll(runners);
        all.addAll(quarantined);
        return Collections.unmodifiableList(all);
    }
}
