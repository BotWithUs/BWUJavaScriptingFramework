package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.model.Personality;
import com.botwithus.bot.core.blueprint.execution.BlueprintBotScript;
import com.botwithus.bot.core.config.ScriptConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a single BotScript on its own virtual thread.
 * Lifecycle: onStart -> loop(onLoop + sleep) -> onStop
 */
public class ScriptRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);
    private final BotScript script;
    private final ScriptContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private volatile CountDownLatch stopLatch;
    private Thread thread;
    private String connectionName;

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(String scriptName, String phase, Throwable error);
    }

    private ErrorHandler errorHandler;
    private final ScriptProfiler profiler = new ScriptProfiler();

    public void setErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    public ScriptProfiler getProfiler() {
        return profiler;
    }

    public ScriptRunner(BotScript script, ScriptContext context) {
        this.script = script;
        this.context = context;
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
        if (latch == null) return true;
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
        if (script instanceof BlueprintBotScript bp) {
            return bp.getMetadata().name();
        }
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
            log.error("Error in onConfigUpdate for {}: {}", getScriptName(), e.getMessage());
        }
    }

    @Override
    public void run() {
        if (connectionName != null) {
            ConnectionContext.set(connectionName);
        }
        String name = getScriptName();
        MDC.put("script.name", name);
        if (connectionName != null) MDC.put("connection.name", connectionName);
        try {
            script.onStart(context);
        } catch (Exception e) {
            log.error("onStart error in {}: {}", name, e.getMessage());
            notifyError(name, "onStart", e);
            running.set(false);
            ConnectionContext.clear();
            return;
        }

        // Load persisted config after onStart
        try {
            List<ConfigField> fields = script.getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(name, fields);
                currentConfig.set(config);
                script.onConfigUpdate(config);
            }
        } catch (Exception e) {
            log.error("Config load error in {}: {}", name, e.getMessage());
        }

        GameAPI gameAPI = context.getGameAPI();
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                long loopStart = System.nanoTime();
                int delay = script.onLoop();
                profiler.recordLoop(System.nanoTime() - loopStart);
                if (delay < 0) break;
                if (delay > 0) {
                    delay = adjustDelay(delay, gameAPI);
                    Thread.sleep(delay);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("onLoop error in {}: {}", name, e.getMessage());
            notifyError(name, "onLoop", e);
        } finally {
            running.set(false);
            try {
                script.onStop();
            } catch (Exception e) {
                log.error("onStop error in {}: {}", name, e.getMessage());
                notifyError(name, "onStop", e);
            }
            try {
                context.getNavigation().cleanup();
            } catch (Exception e) {
                log.debug("Navigation cleanup error in {}: {}", name, e.getMessage());
            }
            MDC.clear();
            ConnectionContext.clear();
            CountDownLatch latch = this.stopLatch;
            if (latch != null) latch.countDown();
        }
    }

    /**
     * Adjusts the script loop delay based on the humanizer personality profile.
     * <p>
     * Factors considered:
     * <ul>
     *   <li><b>Reaction speed</b> – scales the base delay (higher = slower reactions)</li>
     *   <li><b>Pause tendency</b> – adds extra pausing time for pause-heavy personalities</li>
     *   <li><b>Fatigue level</b> – fatigued users slow down (up to +30% at full fatigue)</li>
     *   <li><b>Attention level</b> – low attention adds sluggishness (up to +20%)</li>
     *   <li><b>Rhythm consistency</b> – low consistency adds random jitter (±15%)</li>
     * </ul>
     * If the personality cannot be fetched (humanizer not initialized), the delay is
     * returned unchanged.
     */
    int adjustDelay(int baseDelay, GameAPI gameAPI) {
        Personality p;
        try {
            p = gameAPI.getPersonality();
        } catch (Exception e) {
            return baseDelay;
        }
        if (p == null) return baseDelay;

        Personality.Timing timing = p.timing();
        Personality.Session session = p.session();
        if (timing == null || session == null) return baseDelay;

        // Base scaling: reaction speed (0.7–1.5, centered at 1.0)
        double adjusted = baseDelay * timing.reactionSpeed();

        // Pause tendency: adds proportional extra delay (0.5–2.0, neutral at 1.0)
        // A pause tendency of 1.3 adds 15% extra, 2.0 adds 50%
        adjusted *= (1.0 + (timing.pauseTendency() - 1.0) * 0.5);

        // Fatigue: fatigued users are slower (0.0–1.0 → up to +30%)
        adjusted *= (1.0 + session.fatigueLevel() * 0.3);

        // Attention: low attention adds sluggishness (0.3–1.0 → up to +20%)
        adjusted *= (1.0 + (1.0 - session.attentionLevel()) * 0.2);

        // Rhythm consistency jitter: low consistency → more random variation
        // consistency 1.0 = no jitter, 0.3 = ±15% random spread
        double jitterRange = (1.0 - timing.rhythmConsistency()) * 0.15;
        if (jitterRange > 0.001) {
            double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-jitterRange, jitterRange);
            adjusted *= jitter;
        }

        return Math.max(1, (int) Math.round(adjusted));
    }

    private void notifyError(String scriptName, String phase, Throwable error) {
        ErrorHandler handler = this.errorHandler;
        if (handler != null) {
            try {
                handler.onError(scriptName, phase, error);
            } catch (Exception e) {
                log.error("Error handler itself threw for {}/{}: {}", scriptName, phase, e.getMessage());
            }
        }
    }
}
