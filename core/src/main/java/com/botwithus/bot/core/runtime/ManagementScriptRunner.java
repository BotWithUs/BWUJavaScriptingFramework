package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.config.ScriptConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a single {@link ManagementScript} on its own virtual thread.
 * Mirrors {@link ScriptRunner} but uses {@link ManagementContext} instead of
 * {@link com.botwithus.bot.api.ScriptContext}.
 */
public class ManagementScriptRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ManagementScriptRunner.class);

    /**
     * Synthetic bucket name passed to {@link ScriptConfigStore} for management
     * scripts. Management scripts are cross-client by design, so they don't
     * belong to any real account uuid; the leading underscore is preserved by
     * the store's sanitizer and segregates the directory from real accounts.
     */
    static final String MANAGEMENT_BUCKET = "__management";

    /** See {@link ScriptRunner}'s sentinel: {@code 0} is a legal nanoTime value. */
    private static final long UNSET_NANOS = Long.MIN_VALUE;

    private final ManagementScript script;
    private final ManagementContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private final AtomicReference<ScriptHealth> healthRef =
            new AtomicReference<>(ScriptHealth.HEALTHY);
    private final AtomicReference<Liveness> livenessRef =
            new AtomicReference<>(Liveness.LIVE);
    /** When the current onLoop() began, or {@link #UNSET_NANOS} outside onLoop(). */
    private volatile long loopStartNanos = UNSET_NANOS;
    /** When {@link #stop()} was first requested, or {@link #UNSET_NANOS} while none is pending. */
    private final AtomicLong stopRequestedNanos = new AtomicLong(UNSET_NANOS);
    private final AtomicLong loopCount = new AtomicLong();
    private volatile Runnable watchdogArmer;

    /**
     * Installs the hook that starts the owning runtime's watchdog. See
     * {@link ScriptRunner#setWatchdogArmer} — the CLI and GUI start management
     * scripts by resolving a runner directly, so this must fire from
     * {@link #start()} rather than from the runtime.
     */
    public void setWatchdogArmer(Runnable watchdogArmer) {
        this.watchdogArmer = watchdogArmer;
    }
    private volatile CountDownLatch stopLatch;
    private volatile Thread thread;

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(String scriptName, String phase, Throwable error);
    }

    private ErrorHandler errorHandler;

    public void setErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    public ManagementScriptRunner(ManagementScript script, ManagementContext context) {
        this.script = script;
        this.context = context;
    }

    public void start() {
        Liveness current = livenessRef.get();
        if (current.isTerminal()) {
            log.warn("Refusing to start {}: previous run is {} and its thread has not exited",
                    getScriptName(), current);
            return;
        }
        if (running.compareAndSet(false, true)) {
            // Reset the previous run's stop bookkeeping, or the watchdog would
            // see a stop from minutes ago and quarantine the fresh thread.
            stopRequestedNanos.set(UNSET_NANOS);
            loopStartNanos = UNSET_NANOS;
            livenessRef.set(Liveness.LIVE);
            stopLatch = new CountDownLatch(1);
            String name = getScriptName();
            // rule-exception: {rule:prefer-virtual-threads} — same reasoning as
            // ScriptRunner.start(); see CLAUDE.md "Java rules exceptions".
            // Management scripts are the more privileged (cross-client) runner,
            // so the containment argument applies to them at least as strongly.
            this.thread = Thread.ofPlatform()
                    .name("mgmt-script-" + name)
                    .daemon(true)
                    .priority(ScriptRunner.SCRIPT_THREAD_PRIORITY)
                    .start(this);
            Runnable armer = this.watchdogArmer;
            if (armer != null) {
                armer.run();
            }
        }
    }

    public void stop() {
        running.set(false);
        stopRequestedNanos.compareAndSet(UNSET_NANOS, System.nanoTime());
        Thread t = this.thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Returns the latest {@link ScriptHealth} snapshot. Never {@code null};
     * {@link ScriptHealth#HEALTHY} when the script has never crashed.
     */
    public ScriptHealth health() {
        return healthRef.get();
    }

    /** Returns how responsive this runner is, as judged by the watchdog. */
    public Liveness liveness() {
        return livenessRef.get();
    }

    /** {@code true} once {@link #stop()} has been called on the current run. */
    public boolean isStopRequested() {
        return stopRequestedNanos.get() != UNSET_NANOS;
    }

    /** {@code true} while the script thread exists and has not yet terminated. */
    public boolean isThreadAlive() {
        Thread t = this.thread;
        return t != null && t.isAlive();
    }

    /**
     * Milliseconds the thread has been inside the current {@code onLoop} call,
     * or {@code -1} when it is not inside one.
     */
    long millisInLoop(long nowNanos) {
        long started = loopStartNanos;
        return started == UNSET_NANOS ? -1L : TimeUnit.NANOSECONDS.toMillis(nowNanos - started);
    }

    /**
     * Milliseconds since {@link #stop()} was first requested, or {@code -1}
     * when no stop is pending.
     */
    long millisSinceStopRequested(long nowNanos) {
        long requested = stopRequestedNanos.get();
        return requested == UNSET_NANOS ? -1L : TimeUnit.NANOSECONDS.toMillis(nowNanos - requested);
    }

    /** Flags the runner unresponsive. Recoverable; a completed loop clears it. */
    boolean markStalled() {
        return livenessRef.compareAndSet(Liveness.LIVE, Liveness.STALLED);
    }

    /** Marks the runner cut off from the game. Terminal. */
    boolean markRevoked() {
        Liveness previous = livenessRef.getAndUpdate(
                l -> l == Liveness.ABANDONED ? l : Liveness.REVOKED);
        return previous != Liveness.REVOKED && previous != Liveness.ABANDONED;
    }

    /** Marks the runner written off — revoked and still alive. Terminal. */
    boolean markAbandoned() {
        return livenessRef.getAndSet(Liveness.ABANDONED) != Liveness.ABANDONED;
    }

    /** Snapshot of where the script thread currently is. Empty once it has exited. */
    public StackTraceElement[] threadStackTrace() {
        Thread t = this.thread;
        return t != null ? t.getStackTrace() : new StackTraceElement[0];
    }

    public void dispose() {
        disposed.set(true);
        stop();
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    public boolean awaitStop(long timeoutMs) {
        CountDownLatch latch = this.stopLatch;
        if (latch == null) {
            return true;
        }
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public ManagementScript getScript() {
        return script;
    }

    public ScriptManifest getManifest() {
        return script.getClass().getAnnotation(ScriptManifest.class);
    }

    public String getScriptName() {
        ScriptManifest manifest = getManifest();
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public List<ConfigField> getConfigFields() {
        return script.getConfigFields();
    }

    public ScriptConfig getCurrentConfig() {
        return currentConfig.get();
    }

    public void applyConfig(ScriptConfig config) {
        currentConfig.set(config);
        String name = getScriptName();
        Thread.startVirtualThread(() -> ScriptConfigStore.save(name, MANAGEMENT_BUCKET, config));
        try {
            script.onConfigUpdate(config);
        } catch (Exception e) {
            log.error("Error in onConfigUpdate for {}: {}", getScriptName(), e.getMessage());
        }
    }

    @Override
    public void run() {
        String name = getScriptName();
        MDC.put("script.name", name);
        if (!runOnStart(name)) {
            return;
        }
        loadPersistedConfig(name);
        try {
            runLoop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("onLoop error in {}: {}", name, e.getMessage());
            notifyError(name, Phase.ON_LOOP, e);
        } finally {
            cleanup(name);
        }
    }

    private boolean runOnStart(String name) {
        try {
            script.onStart(context);
            return true;
        } catch (Exception e) {
            log.error("onStart error in {}: {}", name, e.getMessage());
            notifyError(name, Phase.ON_START, e);
            running.set(false);
            return false;
        }
    }

    private void loadPersistedConfig(String name) {
        try {
            List<ConfigField> fields = script.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(name, MANAGEMENT_BUCKET, fields);
                currentConfig.set(config);
                script.onConfigUpdate(config);
            }
        } catch (Exception e) {
            log.error("Config load error in {}: {}", name, e.getMessage());
        }
    }

    private void runLoop() throws InterruptedException {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            loopStartNanos = System.nanoTime();
            int delay;
            try {
                delay = script.onLoop();
            } finally {
                loopStartNanos = UNSET_NANOS;
            }
            loopCount.incrementAndGet();
            livenessRef.compareAndSet(Liveness.STALLED, Liveness.LIVE);
            if (delay < 0) {
                break;
            }
            // Always sleep >=1 ms so interruption is observed each iteration;
            // an onLoop()==0 loop that swallows interruption would otherwise
            // never notice the stop. Blocking *inside* onLoop() can't be killed
            // — ManagementScriptRuntime's watchdog escalates that case instead.
            Thread.sleep(Math.max(1, delay));
        }
    }

    private void cleanup(String name) {
        running.set(false);
        // Clear the interrupt for teardown, then restore it — stop() interrupts
        // the thread, so any blocking call in onStop would otherwise throw
        // InterruptedException immediately. Mirrors ScriptRunner.cleanup.
        boolean wasInterrupted = Thread.interrupted();
        try {
            script.onStop();
        } catch (Exception e) {
            log.error("onStop error in {}: {}", name, e.getMessage());
            notifyError(name, Phase.ON_STOP, e);
        } finally {
            MDC.clear();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
            CountDownLatch latch = this.stopLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    /**
     * Records the failure into {@link #health()} and forwards it to the error
     * handler. Brings this runner up to {@link ScriptRunner}'s telemetry: it is
     * the more privileged, cross-client runner, so having *less* visibility
     * than a plain script was the wrong way round (audit L14).
     */
    private void notifyError(String scriptName, Phase phase, Throwable error) {
        LastCrash crash = new LastCrash(phase, loopCount.get(), Instant.now(), error);
        healthRef.updateAndGet(h -> h.withCrash(crash));
        ErrorHandler handler = this.errorHandler;
        if (handler != null) {
            try {
                handler.onError(scriptName, phaseLabel(phase), error);
            } catch (Exception e) {
                log.error("Error handler threw for {}/{}: {}", scriptName, phase, e.getMessage());
            }
        }
    }

    /** Keeps the handler's phase strings exactly as they were before health was added. */
    private static String phaseLabel(Phase phase) {
        return switch (phase) {
            case ON_START         -> "onStart";
            case ON_LOOP          -> "onLoop";
            case ON_STOP          -> "onStop";
            case ON_CONFIG_UPDATE -> "onConfigUpdate";
        };
    }
}
