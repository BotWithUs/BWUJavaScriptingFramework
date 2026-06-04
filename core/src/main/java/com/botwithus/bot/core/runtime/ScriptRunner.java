package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
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
 * Runs a single BotScript on its own virtual thread.
 * Lifecycle: onStart -> loop(onLoop + sleep) -> onStop
 */
public class ScriptRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);
    private final BotScript script;
    private final ScriptContext context;
    private final Consumer<String> connectionTagger;
    private final Runnable connectionCleaner;
    private final Consumer<GameEvent> eventSink;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private final AtomicReference<ScriptHealth> healthRef =
            new AtomicReference<>(ScriptHealth.HEALTHY);
    private volatile CountDownLatch stopLatch;
    private Thread thread;
    private String connectionName;

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(LastCrash crash);
    }

    private ErrorHandler errorHandler;
    private final ScriptProfiler profiler = new ScriptProfiler();

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
     * Constructs a runner that tags / clears its thread via the supplied callbacks
     * and publishes {@link ScriptCrashedEvent}s through the supplied sink.
     *
     * <p>None of the arguments may be {@code null}. Wiring code that doesn't want
     * to publish crash events should pass {@code e -> {}} explicitly.</p>
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner,
                        Consumer<GameEvent> eventSink) {
        this.script = script;
        this.context = context;
        this.connectionTagger = connectionTagger;
        this.connectionCleaner = connectionCleaner;
        this.eventSink = eventSink;
    }

    /**
     * Three-arg variant without an event sink — crashes are still captured into
     * {@link #health()}, but no {@link ScriptCrashedEvent} is published. Kept
     * for wiring code that doesn't have an {@code EventBus} handy yet.
     */
    public ScriptRunner(BotScript script, ScriptContext context,
                        Consumer<String> connectionTagger, Runnable connectionCleaner) {
        this(script, context, connectionTagger, connectionCleaner, e -> {});
    }

    /**
     * Default-wiring constructor for callers that haven't been migrated to pass
     * a tagger / cleaner explicitly. Routes through {@link ConnectionContext} so
     * the CLI's stdout interception keeps seeing the connection tag on script
     * threads.
     */
    public ScriptRunner(BotScript script, ScriptContext context) {
        this(script, context, ConnectionContext::set, ConnectionContext::clear, e -> {});
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            stopLatch = new CountDownLatch(1);
            String name = getScriptName();
            this.thread = Thread.ofVirtual().name("script-" + name).start(this);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
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
        Thread.startVirtualThread(() -> ScriptConfigStore.save(name, config));
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
        try {
            script.onStart(context);
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
        try {
            List<ConfigField> fields = script.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(name, fields);
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
            int delay = script.onLoop();
            profiler.recordLoop(System.nanoTime() - loopStart);
            if (delay < 0) {
                break;
            }
            if (delay > 0) {
                delay = adjustDelay(delay, gameAPI);
                Thread.sleep(delay);
            }
        }
    }

    private void cleanup(String name) {
        running.set(false);
        try {
            script.onStop();
        } catch (Exception e) {
            log.error("onStop error in {}: {}", name, e.getMessage());
            notifyError(Phase.ON_STOP, e);
        }
        try {
            context.getNavigation().cleanup();
        } catch (Exception e) {
            log.debug("Navigation cleanup error in {}: {}", name, e.getMessage());
        }
        MDC.clear();
        connectionCleaner.run();
        CountDownLatch latch = this.stopLatch;
        if (latch != null) {
            latch.countDown();
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
