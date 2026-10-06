package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.core.impl.ScopedEventBus;
import com.botwithus.bot.core.impl.ScopedMessageBus;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.runlog.AgentIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Manages multiple ScriptRunners and their lifecycles.
 */
public class ScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuntime.class);
    /** How long to wait for a script thread to drain before abandoning it (matches restart paths). */
    private static final long STOP_AWAIT_MS = 2000L;

    /**
     * How long one {@code onLoop} call may run before it is flagged stalled, when
     * nothing has set a threshold. Deliberately generous: a blocking walk
     * legitimately parks inside {@code onLoop} for up to {@code Walker}'s 300 s
     * timeout, so a shorter default would flag healthy scripts.
     */
    public static final long DEFAULT_STALL_AFTER_MS = 600_000L;
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
    private volatile LongSupplier stallAfterMs = () -> DEFAULT_STALL_AFTER_MS;
    private final LivenessWatchdog watchdog = new LivenessWatchdog(
            this::watchdogThreadName, this::watchdogSubjects, () -> stallAfterMs.getAsLong());
    private String connectionName;
    /** Written under {@link #registrationLock}; volatile for {@link #getAccountUuid}. */
    private volatile String accountUuid;
    private Runnable onStateChange;
    private Function<String, ScriptContextPublisher> publisherFactory;
    private ScriptGate scriptGate;
    /** Written under {@link #registrationLock}. */
    private RunnerListener runnerListener = RunnerListener.NONE;
    /**
     * Runs inside {@link #registerScript}, under the registration lock, after the
     * new runner is configured and just before it becomes visible in the runner
     * list. A no-op in production; package-private only so
     * {@code ScriptRuntimeTest} can park a registration there and race
     * {@link #setAccountUuid} against it deterministically.
     */
    private volatile Runnable beforeRunnerPublished = () -> { };
    /** Written under {@link #registrationLock}. */
    private BooleanSupplier loopTimingGate = () -> true;
    // Run-log wiring, read by each runner at the start of each run.
    private volatile RunLogs runLogs;
    private volatile Supplier<KnownNames> runNames = () -> KnownNames.NONE;
    private volatile int slot = RunLogging.NO_SLOT;
    private volatile Supplier<AgentIdentity> agentIdentity = () -> AgentIdentity.UNKNOWN;

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

    /**
     * Sets how long one {@code onLoop} call may run, with no stop pending, before
     * the watchdog flags the runner {@link com.botwithus.bot.api.runtime.Liveness#STALLED}.
     * Read on every sweep, so a supplier over a live setting applies at once.
     * Until this is called the threshold is {@link #DEFAULT_STALL_AFTER_MS}.
     */
    public void setStallThreshold(LongSupplier stallAfterMs) {
        this.stallAfterMs = stallAfterMs;
    }

    /** The stall threshold in milliseconds as it stands now. */
    public long stallThresholdMs() {
        return stallAfterMs.getAsLong();
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
     *
     * <p>Called from connection probe threads while scripts register and run.</p>
     */
    public void setAccountUuid(String accountUuid) {
        // Under the registration lock, so a runner registered concurrently
        // either reads the new uuid or is already in the list below. Without it
        // a registration that read the old value before this write, and joined
        // the list after this loop, kept that old value for the life of the run.
        synchronized (registrationLock) {
            this.accountUuid = accountUuid;
            for (ScriptRunner runner : runners) {
                runner.setAccountUuid(accountUuid);
            }
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
     * Decides whether each runner's profiler keeps its loop aggregates (see
     * {@link ScriptProfiler#setAggregating}). Propagated to every runner,
     * including those already registered and those registered later.
     * {@code null} restores always-on.
     */
    public void setLoopTimingGate(BooleanSupplier gate) {
        BooleanSupplier resolved = gate != null ? gate : () -> true;
        synchronized (registrationLock) {
            this.loopTimingGate = resolved;
            for (ScriptRunner runner : runners) {
                runner.getProfiler().setAggregating(resolved);
            }
        }
    }

    /**
     * Installs the host's observer of script starts, stops and stalls.
     * Propagated to every runner, including those already registered.
     * {@code null} restores {@link RunnerListener#NONE}.
     */
    public void setRunnerListener(RunnerListener listener) {
        RunnerListener resolved = listener != null ? listener : RunnerListener.NONE;
        // Under the registration lock, so a runner registered concurrently
        // either reads the new listener or is already in the list below.
        synchronized (registrationLock) {
            this.runnerListener = resolved;
            for (ScriptRunner runner : runners) {
                runner.setRunnerListener(resolved);
            }
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

    /**
     * Gives this connection's script runs a log file each. Until set, runs keep
     * breadcrumbs and crash summaries but write nothing. Applies from the next
     * run of every runner, registered or not.
     */
    public void setRunLogs(RunLogs runLogs) {
        this.runLogs = runLogs;
    }

    /**
     * The names to redact from this connection's run logs: account and
     * connection names, character names. Re-read for every line written, so a
     * name learned mid-run is redacted from then on.
     */
    public void setRunNames(Supplier<KnownNames> names) {
        this.runNames = names != null ? names : () -> KnownNames.NONE;
    }

    /** The integer a run log's header carries for this connection, in place of its name. */
    public void setSlot(int slot) {
        this.slot = slot;
    }

    /**
     * The seam for the {@code agent_build} and {@code game_revision} header keys.
     * Read once per run. Unbound, both are {@code unknown}; binding the agent's
     * {@code rpc.agent_info} is one call here at the connection setup site.
     */
    public void setAgentIdentity(Supplier<AgentIdentity> agentIdentity) {
        this.agentIdentity = agentIdentity != null ? agentIdentity : () -> AgentIdentity.UNKNOWN;
    }

    /** What a runner of this runtime opens its next run log with. */
    private RunLogging runLogging() {
        RunLogs logs = this.runLogs;
        if (logs == null) {
            return RunLogging.fileLess();
        }
        return new RunLogging(logs, runNames, slot, agentIdentity);
    }

    /** Test seam; see {@link #beforeRunnerPublished}. {@code null} restores the no-op. */
    void setBeforeRunnerPublished(Runnable hook) {
        this.beforeRunnerPublished = hook != null ? hook : () -> { };
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
     *
     * <p>Quarantined runners are deliberately <em>not</em> matched. A zombie is
     * a dead end — it can never be started again — so treating one as "already
     * registered" would make a rebuilt script of the same name unloadable for
     * the life of the process: every reload would hand back the zombie and
     * silently discard the freshly built instance, leaving the old bytecode
     * running behind a UI that claims it reloaded. The fresh runner goes in
     * front of the zombie in {@link #findRunner}'s search order, and the zombie
     * stays visible in {@link #getRunners()} for as long as its thread lives.</p>
     */
    public ScriptRunner registerScript(BotScript script) {
        String name = resolveScriptName(script);
        synchronized (registrationLock) {
            ScriptRunner existing = findActiveRunner(name);
            if (existing != null) {
                return existing;
            }
            // Built before the context so the script's isStopRequested() signal
            // can bind straight to it; the context is then a constructor
            // argument to the runner that owns the same state object.
            RunnerLiveness liveness = new RunnerLiveness();
            ScopedContext scoped = perScriptContextFor(script, liveness);
            ScriptContext perScriptContext = scoped.context();
            ScriptContextPublisher publisher = perScriptContext.getScriptContext();
            ScriptRunner runner = new ScriptRunner(script, perScriptContext, connectionTagger,
                    connectionCleaner, eventSink, publisher, liveness);
            if (scoped.bus() != null) {
                runner.setEventUnsubscriber(scoped.bus()::unsubscribeAll);
            }
            if (scoped.messages() != null) {
                runner.setMessageUnsubscriber(scoped.messages()::unsubscribeAll);
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
            runner.setRunnerListener(runnerListener);
            runner.setRunLogging(this::runLogging);
            runner.getProfiler().setAggregating(loopTimingGate);
            beforeRunnerPublished.run();
            runners.add(runner);
            return runner;
        }
    }

    /**
     * A per-script context together with the two scoped buses inside it. They are
     * carried out separately so the runner can be handed their
     * {@code unsubscribeAll} hooks without re-testing the context's shape; both
     * are null when the context isn't scopable (test mocks).
     */
    private record ScopedContext(ScriptContext context, ScopedEventBus bus,
                                 ScopedMessageBus messages) {}

    /**
     * Builds a per-script {@link ScriptContext}: its own {@link ScopedEventBus}
     * and {@link ScopedMessageBus} always, plus a publisher tagged with the
     * script's name when a factory is installed. Falls back to the shared context
     * unchanged when it isn't a {@link ScriptContextImpl} we can clone.
     */
    private ScopedContext perScriptContextFor(BotScript script, RunnerLiveness liveness) {
        // rule-exception: {rule:no-instanceof} — runtime-shape boundary. ScriptContext
        // is an interface so callers can substitute mocks (see test-support); only the
        // production ScriptContextImpl carries the with-publisher / with-bus hooks.
        if (!(context instanceof ScriptContextImpl impl)) {
            return new ScopedContext(context, null, null);
        }
        // Always scope both buses, publisher factory or not: they are what let
        // cleanup take back the script's subscriptions, and a script whose
        // listeners or ISC handlers outlive it keeps acting on the game after Stop.
        ScopedEventBus bus = new ScopedEventBus(impl.getEventBus());
        ScopedMessageBus messages = new ScopedMessageBus(impl.getMessageBus());
        String name = resolveScriptName(script);
        // Bound straight to the runner's own liveness state, which is created
        // here and handed to the runner below. isStopRequested() is documented
        // as something scripts poll inside long loops, so it must not cost a
        // by-name scan of the runner lists on every call.
        //
        // stopSelf() is the opposite trade: called once, so the by-name lookup is
        // free, and routing it through the runner gives a self-stop the exact
        // path a user Stop takes. That path interrupts the thread, which a bare
        // liveness::requestStop would not: a script sleeping out a long delay
        // after asking to stop would sit past REVOKE_GRACE_MS and be revoked.
        // The runner does not exist yet, so binding to it directly is not an
        // option — but its liveness state does, which is enough to prove at
        // call time that the runner answering to this name is still this run.
        ScriptContextImpl scoped = impl.withEventBus(bus)
                .withScriptMessageBus(messages, name)
                .withStopSignal(liveness::isStopRequested)
                .withStopCallback(() -> stopOwnRun(name, liveness));
        Function<String, ScriptContextPublisher> factory = this.publisherFactory;
        if (factory == null) {
            return new ScopedContext(scoped, bus, messages);
        }
        ScriptContextPublisher publisher = factory.apply(name);
        if (publisher == null || publisher == ScriptContextPublisher.NOOP) {
            return new ScopedContext(scoped, bus, messages);
        }
        return new ScopedContext(scoped.withScriptContext(publisher), bus, messages);
    }

    private static String resolveScriptName(BotScript script) {
        ScriptManifest manifest = script.getClass().getAnnotation(ScriptManifest.class);
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public void startScript(BotScript script) {
        ScriptRunner runner = registerScript(script);
        runner.start();
        log.info("Started script: {}", runner.getScriptName());
        fireStateChange();
    }

    /**
     * Starts the watchdog on first use. Armed from {@link ScriptRunner#start()}
     * via the injected armer, not from {@link #startScript}: the CLI and GUI
     * start scripts by resolving a runner and calling {@code start()} on it, so
     * arming here only would leave the watchdog dead for every user-initiated
     * start.
     */
    private void ensureWatchdog() {
        watchdog.arm();
    }

    /** Tagged with the connection so one client's watchdog is tellable from another's. */
    private String watchdogThreadName() {
        return "script-watchdog" + (connectionName != null ? "-" + connectionName : "");
    }

    /** Active runners followed by quarantined ones — everything the watchdog sweeps. */
    private Iterable<ScriptRunner> watchdogSubjects() {
        return () -> Stream.concat(runners.stream(), quarantined.stream()).iterator();
    }

    /**
     * One watchdog pass. Package-private and time-parameterised so tests can
     * drive the escalation deterministically rather than sleeping through the
     * real grace windows.
     */
    void sweep(long nowNanos) {
        watchdog.sweep(nowNanos);
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
        // still-running script thread risks NoClassDefFoundError.
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
            watchdog.close();
        }
        fireStateChange();
    }

    /**
     * Finds a runner by script name, active or quarantined. Quarantined runners
     * stay addressable so the CLI and GUI can inspect a zombie; they refuse to
     * start again ({@link ScriptRunner#start()} guards on terminal liveness).
     */
    public ScriptRunner findRunner(String name) {
        ScriptRunner active = findActiveRunner(name);
        if (active != null) {
            return active;
        }
        for (ScriptRunner runner : quarantined) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        return null;
    }

    /**
     * Finds a runner among the active ones only. {@code null} when the only
     * runner by that name is a quarantined zombie — which is what lets a
     * reload register a fresh instance over the top of one.
     */
    private ScriptRunner findActiveRunner(String name) {
        for (ScriptRunner runner : runners) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        return null;
    }

    public boolean stopScript(String name) {
        return stopRunner(findRunner(name));
    }

    /**
     * Backs {@code ScriptContext.stopSelf()}. Stops the runner registered under
     * {@code name} only while it is still the run that asked — proven by the
     * {@link RunnerLiveness} instance the two share, which is created per run in
     * {@link #registerScript}.
     *
     * <p>Without that proof a self-stop is just a name lookup, and a name can
     * outlive the run that held it: a quarantined zombie is still executing, can
     * still reach {@code stopSelf()}, and by then the name may belong to a
     * freshly reloaded runner. The zombie would stop its own replacement. The
     * identity check costs one reference comparison on a once-per-run path.</p>
     */
    private void stopOwnRun(String name, RunnerLiveness liveness) {
        ScriptRunner runner = findRunner(name);
        if (runner != null && runner.livenessState() == liveness) {
            stopRunner(runner);
            return;
        }
        log.warn("Ignoring stopSelf() from a retired run of {}: the name now "
                + "belongs to a different runner", name);
    }

    /** Stops a runner that is actually running, and reports whether it did. */
    private boolean stopRunner(ScriptRunner runner) {
        if (runner == null || !runner.isRunning()) {
            return false;
        }
        runner.stop();
        log.info("Stopped script: {}", runner.getScriptName());
        fireStateChange();
        return true;
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

    /**
     * Every runner this runtime knows about — active first, then quarantined
     * zombies. Read per-frame by several GUI panels, so it builds one list
     * rather than copying twice; {@code all} never escapes except wrapped.
     */
    public List<ScriptRunner> getRunners() {
        List<ScriptRunner> all = new ArrayList<>(runners.size() + quarantined.size());
        all.addAll(runners);
        all.addAll(quarantined);
        return Collections.unmodifiableList(all);
    }

    /**
     * Runners whose threads refused to drain and are being kept alive-but-
     * contained. Empty in the normal case.
     */
    public List<ScriptRunner> getQuarantined() {
        return List.copyOf(quarantined);
    }
}
