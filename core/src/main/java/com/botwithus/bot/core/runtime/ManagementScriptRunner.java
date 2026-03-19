package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.config.ScriptConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a single {@link ManagementScript} on its own virtual thread.
 * Mirrors {@link ScriptRunner} but uses {@link ManagementContext} instead of
 * {@link com.botwithus.bot.api.ScriptContext}.
 */
public class ManagementScriptRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ManagementScriptRunner.class);
    private final ManagementScript script;
    private final ManagementContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private volatile CountDownLatch stopLatch;
    private Thread thread;

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
        if (running.compareAndSet(false, true)) {
            stopLatch = new CountDownLatch(1);
            String name = getScriptName();
            this.thread = Thread.ofVirtual().name("mgmt-script-" + name).start(this);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
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
        Thread.startVirtualThread(() -> ScriptConfigStore.save(name, config));
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
        try {
            script.onStart(context);
        } catch (Exception e) {
            log.error("onStart error in {}: {}", name, e.getMessage());
            notifyError(name, "onStart", e);
            running.set(false);
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

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int delay = script.onLoop();
                if (delay < 0) break;
                if (delay > 0) {
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
            MDC.clear();
            CountDownLatch latch = this.stopLatch;
            if (latch != null) latch.countDown();
        }
    }

    private void notifyError(String scriptName, String phase, Throwable error) {
        ErrorHandler handler = this.errorHandler;
        if (handler != null) {
            try {
                handler.onError(scriptName, phase, error);
            } catch (Exception e) {
                log.error("Error handler threw for {}/{}: {}", scriptName, phase, e.getMessage());
            }
        }
    }
}
