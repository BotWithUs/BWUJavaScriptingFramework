package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.impl.ScopedEventBus;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages multiple ScriptRunners and their lifecycles.
 */
public class ScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuntime.class);
    /** How long to wait for a script thread to drain before abandoning it (matches restart paths). */
    private static final long STOP_AWAIT_MS = 2000L;

    /** Watchdog sweep cadence. Fine enough to escalate promptly, coarse enough to be free. */
    private static final long SWEEP_INTERVAL_MS = 250L;

    /**
     * Time inside a single {@code onLoop} with no stop pending before the runner
     * is flagged {@link Liveness#STALLED}. Deliberately generous: a blocking
     * walk legitimately parks inside {@code onLoop} for up to {@code Walker}'s
     * 300s timeout, so a shorter threshold would flag healthy scripts. Advisory
     * only — the runner recovers as soon as a loop completes.
     */
    private static final long IDLE_STALL_MS = 600_000L;

    /** After a stop request, how long the thread may stay in {@code onLoop} before STALLED. */
    static final long STOP_STALL_MS = STOP_AWAIT_MS;

    /** After a stop request, how long before the runner is cut off from the game. */
    static final long REVOKE_GRACE_MS = 5_000L;

    /** After revocation, how long before the thread is written off and quarantined. */
    static final long ABANDON_GRACE_MS = 15_000L;
    private final ScriptContext context;
    private final Consumer<String> connectionTagger;
    private final Runnable connectionCleaner;
    private final Consumer<GameEvent> eventSink;
    private final List<ScriptRunner> runners = new CopyOnWriteArrayList<>();
    /**
     * Runners whose threads refused to drain. They are kept — not dropped — so
     * the zombie stays visible to the user and the watchdog can keep escalating
     * it, and so its script name can't be re-registered on top of a thread that
     * is still running.
     */
    private final List<ScriptRunner> quarantined = new CopyOnWriteArrayList<>();
    /** Guards the check-then-add in {@link #registerScript} so two concurrent
     *  registrations of the same script name can't both append a runner. */
    private final Object registrationLock = new Object();
    /** Guards the lazily-created watchdog executor. */
    private final Object watchdogLock = new Object();
    private ScheduledExecutorService watchdog;
    private String connectionName;
    private String accountUuid;
    private Runnable onStateChange;
    private Function<String, ScriptContextPublisher> publisherFactory;
    private ScriptGate scriptGate;

    /**
     * Constructs a runtime that propagates each runner's connection tag through
     * the supplied callbacks and forwards crash events to {@code eventSink}.
     */
    public ScriptRuntime(ScriptContext context,
                         Consumer<String> connectionTagger,
                         Runnable connectionCleaner,
                         Consumer<GameEvent> eventSink) {
        this.context = context;
        this.connectionTagger = connectionTagger;
        this.connectionCleaner = connectionCleaner;
        this.eventSink = eventSink;
    }

    /**
     * Three-arg variant without a crash-event sink. Crashes are still captured
     * into each runner's {@link ScriptRunner#health()} but no
     * {@link com.botwithus.bot.api.event.ScriptCrashedEvent} is published.
     */
    public ScriptRuntime(ScriptContext context,
                         Consumer<String> connectionTagger,
                         Runnable connectionCleaner) {
        this(context, connectionTagger, connectionCleaner, e -> {});
    }

    public ScriptRuntime(ScriptContext context) {
        this(context, ConnectionContext::set, ConnectionContext::clear, e -> {});
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    /**
     * Sets the stable {@code account_uuid} this runtime is bound to. Propagated
     * to every {@link ScriptRunner} created via {@link #registerScript} so
     * persisted per-script config lands in the right per-account bucket
     * (see {@link com.botwithus.bot.core.config.ScriptConfigStore}). Already-
     * registered runners are updated in place so a uuid that arrives after the
     * runners (e.g. account-info resolves after auto-start registers scripts)
     * still reaches them.
     */
    public void setAccountUuid(String accountUuid) {
        this.accountUuid = accountUuid;
        for (ScriptRunner runner : runners) {
            runner.setAccountUuid(accountUuid);
        }
    }

    public String getAccountUuid() {
        return accountUuid;
    }

    public void setOnStateChange(Runnable callback) {
        this.onStateChange = callback;
    }

    /**
     * Installs the per-connection {@link ScriptGate} used to attribute RPC calls
     * to the script that made them and to cut off a script that ignored a stop.
     * Propagated to every runner, including those already registered.
     *
     * <p>Must be the same instance handed to this connection's
     * {@code RpcClient.setScriptGate}; wired at the connection setup site. When
     * unset, revocation degrades to a no-op and stop stays cooperative-only.</p>
     */
    public void setScriptGate(ScriptGate scriptGate) {
        this.scriptGate = scriptGate;
        for (ScriptRunner runner : runners) {
            runner.setScriptGate(scriptGate);
        }
    }

    /**
     * Installs a factory that yields per-script {@link ScriptContextPublisher}s.
     * When set, every {@link #registerScript} produces a per-script
     * {@link ScriptContext} whose {@code getScriptContext()} returns the
     * publisher tagged with that script's name. When {@code null} (the
     * default), scripts see the shared base context's publisher (typically
     * {@link ScriptContextPublisher#NOOP}).
     *
     * <p>Wired by the connection setup site (e.g. {@code Connection} /
     * {@code CliContext}) after the per-connection
     * {@code ScriptContextChannel} is constructed.</p>
     */
    public void setPublisherFactory(Function<String, ScriptContextPublisher> factory) {
        this.publisherFactory = factory;
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

    /**
     * Registers a script without starting it. Use {@link ScriptRunner#start()} to start later.
     *
     * <p>Idempotent by script name: if a runner with the same name is already
     * registered, the existing runner is returned and no duplicate is added.
     * Reload paths clear the list ({@link #stopAll}) before re-registering, but
     * the auto-start probe registers the full set <em>without</em> a preceding
     * clear; without this guard a refresh that races the probe duplicates the
     * whole script list. The name is the key every consumer ({@link #findRunner},
     * {@link #stopScript}) already uses, so two same-named runners were never
     * addressable anyway.</p>
     */
    public ScriptRunner registerScript(BotScript script) {
        String name = resolveScriptName(script);
        synchronized (registrationLock) {
            ScriptRunner existing = findRunner(name);
            if (existing != null) {
                return existing;
            }
            ScopedContext scoped = perScriptContextFor(script);
            ScriptContext perScriptContext = scoped.context();
            ScriptContextPublisher publisher = perScriptContext.getScriptContext();
            ScriptRunner runner = new ScriptRunner(script, perScriptContext, connectionTagger,
                    connectionCleaner, eventSink, publisher);
            if (scoped.bus() != null) {
                runner.setEventUnsubscriber(scoped.bus()::unsubscribeAll);
            }
            runner.setWatchdogArmer(this::ensureWatchdog);
            if (connectionName != null) {
                runner.setConnectionName(connectionName);
            }
            if (accountUuid != null) {
                runner.setAccountUuid(accountUuid);
            }
            if (scriptGate != null) {
                runner.setScriptGate(scriptGate);
            }
            runners.add(runner);
            return runner;
        }
    }

    /**
     * A per-script context together with the {@link ScopedEventBus} inside it.
     * The bus is carried out separately so the runner can be handed its
     * {@code unsubscribeAll} hook without re-testing the context's shape;
     * {@code bus} is null when the context isn't scopable (test mocks).
     */
    private record ScopedContext(ScriptContext context, ScopedEventBus bus) {}

    /**
     * Builds a per-script {@link ScriptContext}: its own {@link ScopedEventBus}
     * always, plus a publisher tagged with the script's name when a factory is
     * installed. Falls back to the shared context unchanged when it isn't a
     * {@link ScriptContextImpl} we can clone.
     */
    private ScopedContext perScriptContextFor(BotScript script) {
        // rule-exception: {rule:no-instanceof} — runtime-shape boundary. ScriptContext
        // is an interface so callers can substitute mocks (see test-support); only the
        // production ScriptContextImpl carries the with-publisher / with-bus hooks.
        if (!(context instanceof ScriptContextImpl impl)) {
            return new ScopedContext(context, null);
        }
        // Always scope the event bus, publisher factory or not: it is what lets
        // cleanup take back the script's subscriptions, and a script whose
        // listeners outlive it keeps acting on the game after Stop.
        ScopedEventBus bus = new ScopedEventBus(impl.getEventBus());
        String name = resolveScriptName(script);
        // Resolved by name at call time rather than bound to the runner, which
        // doesn't exist yet — the context is a constructor argument to it.
        ScriptContextImpl scoped = impl.withEventBus(bus)
                .withStopSignal(() -> stopRequestedFor(name));
        Function<String, ScriptContextPublisher> factory = this.publisherFactory;
        if (factory == null) {
            return new ScopedContext(scoped, bus);
        }
        ScriptContextPublisher publisher = factory.apply(name);
        if (publisher == null || publisher == ScriptContextPublisher.NOOP) {
            return new ScopedContext(scoped, bus);
        }
        return new ScopedContext(scoped.withScriptContext(publisher), bus);
    }

    /** Backs {@code ScriptContext.isStopRequested()} for the named script. */
    private boolean stopRequestedFor(String name) {
        ScriptRunner runner = findRunner(name);
        return runner != null && runner.isStopRequested();
    }

    private static String resolveScriptName(BotScript script) {
        com.botwithus.bot.api.ScriptManifest manifest =
                script.getClass().getAnnotation(com.botwithus.bot.api.ScriptManifest.class);
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public void startScript(BotScript script) {
        ScriptRunner runner = registerScript(script);
        runner.start();
        log.info("Started script: {}", runner.getScriptName());
        fireStateChange();
    }

    /**
     * Starts the watchdog on first use. Lazy so that the many test seams which
     * build a {@link ScriptRuntime} without ever starting a script don't each
     * spawn a thread.
     *
     * <p>Armed from {@link ScriptRunner#start()} via the injected armer, not
     * from {@link #startScript}: the CLI and GUI start scripts by resolving a
     * runner and calling {@code start()} on it, so arming here only would leave
     * the watchdog dead for every user-initiated start.</p>
     */
    private void ensureWatchdog() {
        synchronized (watchdogLock) {
            if (watchdog != null) {
                return;
            }
            String suffix = connectionName != null ? "-" + connectionName : "";
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "script-watchdog" + suffix);
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
            // A watchdog that dies on one bad runner stops protecting the rest,
            // so swallow and keep sweeping.
            log.error("Watchdog sweep failed: {}", e.getMessage());
        }
    }

    /**
     * One watchdog pass over the registered runners. Package-private and
     * time-parameterised so tests can drive the escalation deterministically
     * rather than sleeping through the real grace windows.
     */
    void sweep(long nowNanos) {
        for (ScriptRunner runner : runners) {
            sweepRunner(runner, nowNanos);
        }
        for (ScriptRunner runner : quarantined) {
            sweepRunner(runner, nowNanos);
        }
    }

    /**
     * Escalates a single runner. With no stop pending this only ever raises the
     * advisory stall flag; once a stop is pending the runner walks
     * STALLED → REVOKED → ABANDONED as each grace window elapses.
     */
    private void sweepRunner(ScriptRunner runner, long nowNanos) {
        if (runner.liveness() == Liveness.ABANDONED) {
            return;
        }
        long sinceStopMs = runner.millisSinceStopRequested(nowNanos);
        if (sinceStopMs < 0L) {
            flagIdleStall(runner, nowNanos);
            return;
        }
        if (!runner.isThreadAlive()) {
            return;
        }
        if (sinceStopMs >= ABANDON_GRACE_MS) {
            abandon(runner);
        } else if (sinceStopMs >= REVOKE_GRACE_MS) {
            revoke(runner);
        } else if (sinceStopMs >= STOP_STALL_MS && runner.markStalled()) {
            log.warn("Script {} has not stopped after {} ms; still inside onLoop()",
                    runner.getScriptName(), sinceStopMs);
        }
    }

    /** Advisory only: nobody asked this script to stop, it's just been in one loop a long time. */
    private void flagIdleStall(ScriptRunner runner, long nowNanos) {
        long inLoopMs = runner.millisInLoop(nowNanos);
        if (inLoopMs >= IDLE_STALL_MS && runner.markStalled()) {
            log.warn("Script {} has been inside a single onLoop() for {} ms",
                    runner.getScriptName(), inLoopMs);
        }
    }

    private void revoke(ScriptRunner runner) {
        if (runner.markRevoked()) {
            log.warn("Script {} ignored stop; revoking its access to the game",
                    runner.getScriptName());
        }
    }

    private void abandon(ScriptRunner runner) {
        if (!runner.markAbandoned()) {
            return;
        }
        // The thread is unkillable, so its classes must stay loaded for as long
        // as it runs. Pinning leaks the loader deliberately; closing it would
        // give the live thread NoClassDefFoundError and, on Windows, wedge
        // every later reload behind a JAR handle it can't release anyway.
        LocalScriptLoader.pinLoaderOf(runner.getScript());
        log.error("Script {} survived revocation; quarantining its thread and pinning its classloader",
                runner.getScriptName());
    }

    public void startAll(List<BotScript> scripts) {
        for (BotScript script : scripts) {
            startScript(script);
        }
    }

    public void stopAll() {
        for (ScriptRunner runner : runners) {
            runner.dispose();
            log.info("Stopped script: {}", runner.getScriptName());
        }
        // Wait for each runner's thread to actually drain before the caller
        // proceeds to reload (which closes the script ClassLoaders). dispose()
        // only interrupts cooperatively; closing a loader out from under a
        // still-running script thread risks NoClassDefFoundError and, on
        // Windows, leaks the JAR file handle and wedges the next reload.
        //
        // A thread that won't drain is *quarantined*, not forgotten: we can't
        // kill it, so it is kept visible and left to the watchdog to revoke and
        // abandon. Dropping the reference here is what used to make a runaway
        // script invisible while it carried on playing the game.
        for (ScriptRunner runner : runners) {
            if (!runner.awaitStop(STOP_AWAIT_MS)) {
                log.warn("Script {} did not stop within {} ms; quarantining it",
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

    /**
     * Finds a runner by script name, active or quarantined. Quarantined runners
     * stay addressable so the CLI and GUI can inspect a zombie; they refuse to
     * start again ({@link ScriptRunner#start()} guards on terminal liveness).
     */
    public ScriptRunner findRunner(String name) {
        for (ScriptRunner runner : runners) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        for (ScriptRunner runner : quarantined) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        return null;
    }

    public boolean stopScript(String name) {
        ScriptRunner runner = findRunner(name);
        if (runner != null && runner.isRunning()) {
            runner.stop();
            log.info("Stopped script: {}", runner.getScriptName());
            fireStateChange();
            return true;
        }
        return false;
    }

    /**
     * Removes a stopped runner. Refuses while the script's thread is still
     * alive — a quarantined zombie must stay in the list, or it becomes
     * invisible again while it is still running.
     */
    public boolean removeScript(String name) {
        ScriptRunner runner = findRunner(name);
        if (runner == null || runner.isRunning() || runner.isThreadAlive()) {
            return false;
        }
        runner.dispose();
        runners.remove(runner);
        quarantined.remove(runner);
        return true;
    }

    /** Every runner this runtime knows about — active first, then quarantined zombies. */
    public List<ScriptRunner> getRunners() {
        List<ScriptRunner> all = new ArrayList<>(runners);
        all.addAll(quarantined);
        return List.copyOf(all);
    }

    /**
     * Runners whose threads refused to drain and are being kept alive-but-
     * contained. Empty in the normal case.
     */
    public List<ScriptRunner> getQuarantined() {
        return List.copyOf(quarantined);
    }
}
