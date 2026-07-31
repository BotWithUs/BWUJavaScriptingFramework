package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of {@link ManagementScript} instances.
 * Analogous to {@link ScriptRuntime} but for connection-independent scripts.
 */
public class ManagementScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ManagementScriptRuntime.class);
    /** How long to wait for a management-script thread to drain before abandoning it. */
    private static final long STOP_AWAIT_MS = 2000L;

    /** Watchdog sweep cadence. Mirrors {@link ScriptRuntime}. */
    private static final long SWEEP_INTERVAL_MS = 250L;
    /** Time in one {@code onLoop} with no stop pending before an advisory STALLED flag. */
    private static final long IDLE_STALL_MS = 600_000L;
    /** After a stop request, how long the thread may stay in {@code onLoop} before STALLED. */
    private static final long STOP_STALL_MS = STOP_AWAIT_MS;
    /** After a stop request, how long before the runner is written off as revoked. */
    private static final long REVOKE_GRACE_MS = 5_000L;
    /** After revocation, how long before the thread is quarantined. */
    private static final long ABANDON_GRACE_MS = 15_000L;

    private final ManagementContext context;
    private final List<ManagementScriptRunner> runners = new CopyOnWriteArrayList<>();
    /** Runners whose threads refused to drain; kept visible rather than dropped. */
    private final List<ManagementScriptRunner> quarantined = new CopyOnWriteArrayList<>();
    private final Object watchdogLock = new Object();
    private ScheduledExecutorService watchdog;
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
        synchronized (watchdogLock) {
            if (watchdog != null) {
                return;
            }
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mgmt-script-watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleWithFixedDelay(this::sweepNow,
                    SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stopWatchdog() {
        synchronized (watchdogLock) {
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
        }
    }

    private void sweepNow() {
        try {
            sweep(System.nanoTime());
        } catch (Exception e) {
            log.error("Management watchdog sweep failed: {}", e.getMessage());
        }
    }

    /** One watchdog pass. Time-parameterised so tests can drive it deterministically. */
    void sweep(long nowNanos) {
        for (ManagementScriptRunner runner : runners) {
            sweepRunner(runner, nowNanos);
        }
        for (ManagementScriptRunner runner : quarantined) {
            sweepRunner(runner, nowNanos);
        }
    }

    private void sweepRunner(ManagementScriptRunner runner, long nowNanos) {
        if (runner.liveness() == Liveness.ABANDONED) {
            return;
        }
        long sinceStopMs = runner.millisSinceStopRequested(nowNanos);
        if (sinceStopMs < 0L) {
            long inLoopMs = runner.millisInLoop(nowNanos);
            if (inLoopMs >= IDLE_STALL_MS && runner.markStalled()) {
                log.warn("Management script {} has been inside a single onLoop() for {} ms",
                        runner.getScriptName(), inLoopMs);
            }
            return;
        }
        if (!runner.isThreadAlive()) {
            return;
        }
        if (sinceStopMs >= ABANDON_GRACE_MS) {
            if (runner.markAbandoned()) {
                // Same reasoning as ScriptRuntime.abandon: the thread can't be
                // killed, so its classes must stay loaded or the next reload
                // closes the loader out from under it.
                ManagementScriptLoader.pinLoaderOf(runner.getScript());
                log.error("Management script {} will not stop; quarantining its thread "
                        + "and pinning its classloader", runner.getScriptName());
            }
        } else if (sinceStopMs >= REVOKE_GRACE_MS) {
            if (runner.markRevoked()) {
                // Note this is observational for management scripts: ScriptGate
                // is per-connection and a management script is cross-client by
                // design, so there is no single RPC choke point to cut it off
                // at. The flag surfaces the state; it does not enforce it.
                log.warn("Management script {} ignored stop and cannot be cut off "
                        + "(cross-client, no per-connection gate)", runner.getScriptName());
            }
        } else if (sinceStopMs >= STOP_STALL_MS && runner.markStalled()) {
            log.warn("Management script {} has not stopped after {} ms",
                    runner.getScriptName(), sinceStopMs);
        }
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
            stopWatchdog();
        }
        fireStateChange();
    }

    /** Returns all known runners — active first, then quarantined zombies. */
    public List<ManagementScriptRunner> getRunners() {
        List<ManagementScriptRunner> all = new ArrayList<>(runners);
        all.addAll(quarantined);
        return List.copyOf(all);
    }
}
