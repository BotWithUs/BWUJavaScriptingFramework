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
    private final ScriptContext context;
    private final Consumer<String> connectionTagger;
    private final Runnable connectionCleaner;
    private final Consumer<GameEvent> eventSink;
    private final List<ScriptRunner> runners = new CopyOnWriteArrayList<>();
    private String connectionName;
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
     */
    public ScriptRunner registerScript(BotScript script) {
        ScriptContext perScriptContext = perScriptContextFor(script);
        ScriptContextPublisher publisher = perScriptContext.getScriptContext();
        ScriptRunner runner = new ScriptRunner(script, perScriptContext, connectionTagger,
                connectionCleaner, eventSink, publisher);
        if (connectionName != null) {
            runner.setConnectionName(connectionName);
        }
        runners.add(runner);
        return runner;
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
