package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.core.config.ScriptConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs a single BotScript on its own platform thread (see {@link #start()} for
 * why it isn't a virtual one).
 * Lifecycle: onStart -> loop(onLoop + sleep) -> onStop
 */
public class ScriptRunner implements Runnable, LivenessWatchdog.Subject {

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    /** Lifecycle state strings emitted on the {@code script.context} broker topic. */
    private static final String STATE_STARTING  = "STARTING";
    private static final String STATE_RUNNING   = "RUNNING";
    private static final String STATE_STOPPED   = "STOPPED";
    private static final String STATE_CRASHED   = "CRASHED";
    private static final String STATE_STALLED   = "STALLED";
    private static final String STATE_REVOKED   = "REVOKED";
    private static final String STATE_ABANDONED = "ABANDONED";

    /**
     * Script threads run one notch below normal so the host's own machinery
     * (RPC reader, event pump, GUI) still wins the CPU when a script is busy.
     * Shared with {@link ManagementScriptRunner}.
     */
    static final int SCRIPT_THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;

    private final BotScript script;
    private final ScriptContext context;
    private final Consumer<String> connectionTagger;
    private final Runnable connectionCleaner;
    private final Consumer<GameEvent> eventSink;
    private final ScriptContextPublisher scriptCtxPublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private final AtomicReference<ScriptHealth> healthRef =
            new AtomicReference<>(ScriptHealth.HEALTHY);
    private final RunnerLiveness livenessState;
    private volatile CountDownLatch stopLatch;
    private volatile Thread thread;
    private String connectionName;
    private String accountUuid;

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(LastCrash crash);
    }

    private ErrorHandler errorHandler;
    private final ScriptProfiler profiler = new ScriptProfiler();
    private volatile ScriptGate scriptGate;
    private volatile Runnable eventUnsubscriber;
    private volatile Runnable watchdogArmer;

    /**
     * Installs the hook that starts the owning runtime's watchdog. Invoked from
     * {@link #start()} so it fires on every start path — including the CLI and
     * GUI, which start a script by resolving its runner rather than going
     * through {@link ScriptRuntime#startScript}. Null in test seams.
     */
    public void setWatchdogArmer(Runnable watchdogArmer) {
        this.watchdogArmer = watchdogArmer;
    }

    /**
     * Installs the per-connection gate this runner tags its thread with. Set by
     * {@link ScriptRuntime#registerScript}; left null in test seams, where no
     * revocation happens.
     */
    public void setScriptGate(ScriptGate scriptGate) {
        this.scriptGate = scriptGate;
    }

    /**
     * Installs the hook that drops this script's event subscriptions when it
     * stops. Set by {@link ScriptRuntime#registerScript} to the per-script
     * {@link com.botwithus.bot.core.impl.ScopedEventBus}; null in test seams.
     */
    public void setEventUnsubscriber(Runnable eventUnsubscriber) {
        this.eventUnsubscriber = eventUnsubscriber;
    }

    public void setErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    public ScriptProfiler getProfiler() {
        return profiler;
    }

    /**
     * Returns the latest {@link ScriptHealth} snapshot for this runner. Never
     * {@code null}; {@link ScriptHealth#HEALTHY} when the script has never
     * crashed.
     */
    public ScriptHealth health() {
        return healthRef.get();
    }

    /**
     * Returns how responsive this runner is, as judged by the watchdog. Never
     * {@code null}.
     */
    public Liveness liveness() {
        return livenessState.get();
    }

    /** The mutable liveness state the watchdog drives. */
    @Override
    public RunnerLiveness livenessState() {
        return livenessState;
    }

    /** {@code true} once {@link #stop()} has been called on the current run. */
    public boolean isStopRequested() {
        return livenessState.isStopRequested();
    }

    /** {@code true} while the script thread exists and has not yet terminated. */
    @Override
    public boolean isThreadAlive() {
        Thread t = this.thread;
        return t != null && t.isAlive();
    }

    /** Cuts this script off from the game; every later RPC from it throws. */
    @Override
    public void revokeAccess() {
        ScriptGate gate = this.scriptGate;
        if (gate != null) {
            gate.revoke(getScriptName());
        }
    }

    /** Keeps this script's classes loaded for as long as its thread runs. */
    @Override
    public void pinClassLoader() {
        LocalScriptLoader.pinLoaderOf(script);
    }

    /** Mirrors each watchdog transition onto the {@code script.context} topic. */
    @Override
    public void onLivenessChanged(Liveness to) {
        switch (to) {
            case STALLED   -> publishState(STATE_STALLED, "unresponsive inside onLoop()");
            case REVOKED   -> publishState(STATE_REVOKED, "did not stop; cut off from the game");
            case ABANDONED -> publishState(STATE_ABANDONED, "thread survived revocation; quarantined");
            case LIVE      -> { }
        }
    }

    /**
     * Snapshot of where the script thread currently is, for surfacing a stuck
     * runner to the user. Empty when the thread has terminated or never ran.
     */
    public StackTraceElement[] threadStackTrace() {
        Thread t = this.thread;
        return t != null ? t.getStackTrace() : new StackTraceElement[0];
    }

    /**
     * Constructs a runner with an explicit {@link ScriptContextPublisher} to
     * emit lifecycle state changes to. {@code scriptCtxPublisher} may be
     * {@link ScriptContextPublisher#NOOP} (or null, treated as NOOP) when no
     * debugger channel is wired.
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner,
                        Consumer<GameEvent> eventSink,
                        ScriptContextPublisher scriptCtxPublisher) {
        this(script, context, connectionTagger, connectionCleaner, eventSink,
                scriptCtxPublisher, new RunnerLiveness());
    }

    /**
     * Canonical constructor, taking the {@link RunnerLiveness} the caller has
     * already created. {@link ScriptRuntime#registerScript} uses this so the
     * script's {@code isStopRequested()} signal can be bound straight to the
     * state object — the context is a constructor argument to this runner, so
     * it cannot reference the runner itself.
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner,
                        Consumer<GameEvent> eventSink,
                        ScriptContextPublisher scriptCtxPublisher,
                        RunnerLiveness livenessState) {
        this.script = script;
        this.context = context;
        this.connectionTagger = connectionTagger;
        this.connectionCleaner = connectionCleaner;
        this.eventSink = eventSink;
        this.scriptCtxPublisher = scriptCtxPublisher != null
                ? scriptCtxPublisher : ScriptContextPublisher.NOOP;
        this.livenessState = livenessState != null ? livenessState : new RunnerLiveness();
    }

    /**
     * Constructs a runner that tags / clears its thread via the supplied callbacks
     * and publishes {@link ScriptCrashedEvent}s through the supplied sink.
     *
     * <p>None of the arguments may be {@code null}. Wiring code that doesn't want
     * to publish crash events should pass {@code e -> {}} explicitly.</p>
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner,
                        Consumer<GameEvent> eventSink) {
        this(script, context, connectionTagger, connectionCleaner, eventSink,
                ScriptContextPublisher.NOOP);
    }

    /**
     * Three-arg variant without an event sink — crashes are still captured into
     * {@link #health()}, but no {@link ScriptCrashedEvent} is published. Kept
     * for wiring code that doesn't have an {@code EventBus} handy yet.
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner) {
        this(script, context, connectionTagger, connectionCleaner, e -> {},
                ScriptContextPublisher.NOOP);
    }

    /**
     * Default-wiring constructor for callers that haven't been migrated to pass
     * a tagger / cleaner explicitly. Routes through {@link ConnectionContext} so
     * the CLI's stdout interception keeps seeing the connection tag on script
     * threads.
     */
    public ScriptRunner(BotScript script, ScriptContext context) {
        this(script, context, ConnectionContext::set, ConnectionContext::clear, e -> {},
                ScriptContextPublisher.NOOP);
    }

    public void start() {
        Liveness current = livenessState.get();
        if (current.isTerminal()) {
            // The previous run's thread is still alive and can't be killed.
            // Starting a second one would put two copies of the script on the
            // same client. Guarded here rather than at each call site so every
            // start path (auto-start, CLI, GUI, restart) is covered.
            log.warn("Refusing to start {}: previous run is {} and its thread has not exited",
                    getScriptName(), current);
            return;
        }
        if (running.compareAndSet(false, true)) {
            // Reset the stop bookkeeping before the thread exists. A runner is
            // reused across restarts, so leaving the previous run's stop
            // timestamp in place would make the watchdog see a stop that
            // happened minutes ago, revoke the freshly-started script and
            // quarantine it — and would make isStopRequested() true on its very
            // first loop. Safe to force LIVE here: terminal states returned above.
            livenessState.resetForRestart();
            stopLatch = new CountDownLatch(1);
            String name = getScriptName();
            // rule-exception: {rule:prefer-virtual-threads} — see CLAUDE.md
            // "Java rules exceptions". Virtual threads are never preempted: a
            // script that spins in onLoop() without blocking pins its carrier
            // forever, and availableProcessors() such scripts starve every
            // other virtual thread in the JVM — including rpc-reader, which
            // wedges RPC for every connected client. Script runners are few,
            // long-lived, CPU-active each loop and run untrusted third-party
            // code, so they are the anti-pattern for virtual threads. On a
            // platform thread the OS preempts a runaway script and it costs
            // CPU share and nothing else.
            this.thread = Thread.ofPlatform()
                    .name("script-" + name)
                    .daemon(true)
                    .priority(SCRIPT_THREAD_PRIORITY)
                    .start(this);
            // Arm the watchdog here, not at the runtime's startScript(): the
            // CLI and GUI both start scripts by resolving a runner and calling
            // this method directly, so arming further up would leave the
            // watchdog unstarted for every user-initiated start.
            Runnable armer = this.watchdogArmer;
            if (armer != null) {
                armer.run();
            }
        }
    }

    public void stop() {
        running.set(false);
        livenessState.requestStop();
        Thread t = this.thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Marks this runner as disposed (removed from the runtime).
     * GUI panels should check {@link #isDisposed()} and close when true.
     */
    public void dispose() {
        disposed.set(true);
        stop();
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    /**
     * Blocks until the script thread has finished, or until the timeout expires.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if the script stopped within the timeout
     */
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

    public BotScript getScript() {
        return script;
    }

    public ScriptManifest getManifest() {
        return script.getClass().getAnnotation(ScriptManifest.class);
    }

    public String getScriptName() {
        ScriptManifest manifest = getManifest();
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    /**
     * Sets the stable {@code account_uuid} this runner persists its config
     * under. When {@code null}, persistence is skipped (load returns defaults,
     * save is a no-op) — this only happens in test seams where the runner is
     * constructed without going through {@link ScriptRuntime#registerScript}.
     */
    public void setAccountUuid(String accountUuid) {
        this.accountUuid = accountUuid;
    }

    public String getAccountUuid() {
        return accountUuid;
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
        String name = getScriptName();
        String uuid = accountUuid;
        if (uuid == null) {
            log.info("applyConfig({}): no accountUuid set, skipping persist", name);
        } else {
            Thread.startVirtualThread(() -> ScriptConfigStore.save(name, uuid, config));
        }
        try {
            script.onConfigUpdate(config);
        } catch (Exception e) {
            log.error("Error in onConfigUpdate for {}: {}", name, e.getMessage());
            notifyError(Phase.ON_CONFIG_UPDATE, e);
        }
    }

    @Override
    public void run() {
        if (connectionName != null) {
            connectionTagger.accept(connectionName);
        }
        String name = getScriptName();
        // Tag before any script code runs. The tag is inheritable, so threads
        // the script spawns (notably the walk executor) are attributed back to
        // it and are covered by the same revocation.
        ScriptGate gate = this.scriptGate;
        if (gate != null) {
            gate.enter(name);
        }
        MDC.put("script.name", name);
        if (connectionName != null) {
            MDC.put("connection.name", connectionName);
        }
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
            notifyError(Phase.ON_LOOP, e);
        } finally {
            cleanup(name);
        }
    }

    private boolean runOnStart(String name) {
        publishState(STATE_STARTING, null);
        try {
            script.onStart(context);
            publishState(STATE_RUNNING, null);
            return true;
        } catch (Exception e) {
            log.error("onStart error in {}: {}", name, e.getMessage());
            notifyError(Phase.ON_START, e);
            running.set(false);
            connectionCleaner.run();
            return false;
        }
    }

    private void loadPersistedConfig(String name) {
        if (accountUuid == null) {
            log.info("loadPersistedConfig({}): no accountUuid set, using field defaults", name);
            return;
        }
        try {
            List<ConfigField> fields = script.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(name, accountUuid, fields);
                currentConfig.set(config);
                script.onConfigUpdate(config);
            }
        } catch (Exception e) {
            log.error("Config load error in {}: {}", name, e.getMessage());
            notifyError(Phase.ON_CONFIG_UPDATE, e);
        }
    }

    private void runLoop() throws InterruptedException {
        GameAPI gameAPI = context.getGameAPI();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long loopStart = System.nanoTime();
            livenessState.enterLoop();
            int delay;
            try {
                delay = script.onLoop();
            } finally {
                // Also clears an advisory stall; terminal states stick.
                livenessState.exitLoop();
            }
            profiler.recordLoop(System.nanoTime() - loopStart);
            if (delay < 0) {
                break;
            }
            // Always sleep at least 1 ms so interruption is observed every
            // iteration; a tight onLoop()==0 loop that swallows
            // InterruptedException would otherwise never notice the stop.
            // A script blocking *inside* onLoop() still cannot be force-killed
            // (no safe Thread.stop in modern Java) — that case is handled by
            // ScriptRuntime's watchdog escalating to REVOKED and then
            // ABANDONED, which contains the thread rather than terminating it.
            delay = adjustDelay(delay, gameAPI);
            Thread.sleep(Math.max(1, delay));
        }
    }

    private void cleanup(String name) {
        running.set(false);
        // Clear the interrupt for the duration of teardown, then restore it.
        // stop() interrupts the thread, so by the time we get here the flag is
        // almost always set — and every blocking call below (onStop, and the
        // join inside the walk cancel) would throw InterruptedException
        // immediately, skipping the very quiescing this method exists to do.
        boolean wasInterrupted = Thread.interrupted();
        try {
            cleanupPhases(name);
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
            CountDownLatch latch = this.stopLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private void cleanupPhases(String name) {
        try {
            script.onStop();
        } catch (Exception e) {
            log.error("onStop error in {}: {}", name, e.getMessage());
            notifyError(Phase.ON_STOP, e);
        }
        // Drop this script's event subscriptions before releasing anything else.
        // EventBusImpl dispatches inline on the event-pump thread, so a listener
        // left registered keeps running — and can keep driving the game — long
        // after the script that registered it has stopped.
        Runnable unsubscriber = this.eventUnsubscriber;
        if (unsubscriber != null) {
            try {
                unsubscriber.run();
            } catch (Exception e) {
                log.debug("Event unsubscribe error in {}: {}", name, e.getMessage());
            }
        }
        // Cancels *and joins* the walk executor this script started (owner-
        // scoped, so a sibling's walk is left alone). Without the join, stop
        // returns while ww-executor is still queueing actions.
        try {
            context.getNavigation().cleanup();
        } catch (Exception e) {
            log.debug("Navigation cleanup error in {}: {}", name, e.getMessage());
        }
        publishState(STATE_STOPPED, null);
        MDC.clear();
        // Only a clean exit clears the tag. A zombie never reaches here, so it
        // keeps its tag — which is what lets the gate keep rejecting it. Any
        // thread the script spawned holds its own inherited copy and is
        // likewise unaffected, so an outliving walk executor stays revokable.
        ScriptGate gate = this.scriptGate;
        if (gate != null) {
            gate.exit();
        }
        connectionCleaner.run();
    }

    private void publishState(String state, String detail) {
        try {
            if (detail == null) {
                scriptCtxPublisher.state(state);
            } else {
                scriptCtxPublisher.state(state, detail);
            }
        } catch (RuntimeException e) {
            log.debug("script.context state publish threw: {}", e.getMessage());
        }
    }

    /**
     * Stub: returns the base delay unchanged. The pre-rewrite implementation
     * called {@code GameAPI.getPersonality()} to scale loop delays by the
     * producer-side humanizer profile. That RPC was dropped in slice 3 —
     * the humanizer state lives in C++ and isn't exposed to hosts yet. Add
     * a snapshot/RPC bridge in a follow-up slice if scripts need this back.
     */
    int adjustDelay(int baseDelay, GameAPI gameAPI) {
        return baseDelay;
    }

    private void notifyError(Phase phase, Throwable error) {
        LastCrash crash = new LastCrash(phase, profiler.getLoopCount(), Instant.now(), error);
        healthRef.updateAndGet(h -> h.withCrash(crash));
        publishState(STATE_CRASHED, phase + ": " + (error != null ? error.getMessage() : "?"));
        ErrorHandler handler = this.errorHandler;
        if (handler != null) {
            try {
                handler.onError(crash);
            } catch (Exception e) {
                log.error("Error handler itself threw for {}/{}: {}",
                        getScriptName(), phase, e.getMessage());
            }
        }
        try {
            eventSink.accept(new ScriptCrashedEvent(getScriptName(), connectionName, crash));
        } catch (Exception e) {
            log.warn("Event sink threw on ScriptCrashedEvent for {}/{}: {}",
                    getScriptName(), phase, e.getMessage());
        }
    }
}
