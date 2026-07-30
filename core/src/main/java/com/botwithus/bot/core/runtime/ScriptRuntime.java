package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages multiple ScriptRunners and their lifecycles.
 */
public class ScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuntime.class);
    /** How long to wait for a script thread to drain before abandoning it (matches restart paths). */
    private static final long STOP_AWAIT_MS = 2000L;
    private final ScriptContext context;
    private final Consumer<String> connectionTagger;
    private final Runnable connectionCleaner;
    private final Consumer<GameEvent> eventSink;
    private final List<ScriptRunner> runners = new CopyOnWriteArrayList<>();
    /** Guards the check-then-add in {@link #registerScript} so two concurrent
     *  registrations of the same script name can't both append a runner. */
    private final Object registrationLock = new Object();
    private String connectionName;
    private String accountUuid;
    private Runnable onStateChange;
    private Function<String, ScriptContextPublisher> publisherFactory;

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
            ScriptContext perScriptContext = perScriptContextFor(script);
            ScriptContextPublisher publisher = perScriptContext.getScriptContext();
            ScriptRunner runner = new ScriptRunner(script, perScriptContext, connectionTagger,
                    connectionCleaner, eventSink, publisher);
            if (connectionName != null) {
                runner.setConnectionName(connectionName);
            }
            if (accountUuid != null) {
                runner.setAccountUuid(accountUuid);
            }
            runners.add(runner);
            return runner;
        }
    }

    /**
     * Builds a per-script {@link ScriptContext} whose publisher is tagged with
     * the script's name. If the factory isn't set, or the shared context isn't
     * a {@link ScriptContextImpl} we can clone, falls back to the shared
     * context unchanged.
     */
    private ScriptContext perScriptContextFor(BotScript script) {
        Function<String, ScriptContextPublisher> factory = this.publisherFactory;
        if (factory == null) {
            return context;
        }
        // rule-exception: {rule:no-instanceof} — runtime-shape boundary. ScriptContext
        // is an interface so callers can substitute mocks (see test-support); only the
        // production ScriptContextImpl carries the with-publisher hook.
        if (!(context instanceof ScriptContextImpl impl)) {
            return context;
        }
        String name = resolveScriptName(script);
        ScriptContextPublisher publisher = factory.apply(name);
        if (publisher == null || publisher == ScriptContextPublisher.NOOP) {
            return context;
        }
        return impl.withScriptContext(publisher);
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
        // Windows, leaks the JAR file handle and wedges the next reload. This
        // is best-effort: a script that ignores interruption is abandoned, not
        // waited on forever.
        for (ScriptRunner runner : runners) {
            if (!runner.awaitStop(STOP_AWAIT_MS)) {
                log.warn("Script {} did not stop within {} ms; abandoning its thread",
                        runner.getScriptName(), STOP_AWAIT_MS);
            }
        }
        runners.clear();
        fireStateChange();
    }

    public ScriptRunner findRunner(String name) {
        for (ScriptRunner runner : runners) {
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

    public boolean removeScript(String name) {
        ScriptRunner runner = findRunner(name);
        if (runner != null && !runner.isRunning()) {
            runner.dispose();
            runners.remove(runner);
            return true;
        }
        return false;
    }

    public List<ScriptRunner> getRunners() {
        return List.copyOf(runners);
    }
}
