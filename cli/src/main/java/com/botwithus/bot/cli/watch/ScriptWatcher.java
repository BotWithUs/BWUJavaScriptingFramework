package com.botwithus.bot.cli.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the scripts/ directory for JAR file changes and triggers a callback.
 */
public class ScriptWatcher {

    private static final Logger log = LoggerFactory.getLogger(ScriptWatcher.class);

    private final Path scriptsDir;
    private final Runnable onChange;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchThread;

    public ScriptWatcher(Path scriptsDir, Runnable onChange) {
        this.scriptsDir = scriptsDir;
        this.onChange = onChange;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        watchThread = Thread.ofVirtual().name("script-watcher").start(this::watchLoop);
    }

    public void stop() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void watchLoop() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            scriptsDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            long lastTrigger = 0;

            while (running.get()) {
                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                boolean jarChanged = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path path = (Path) event.context();
                    if (path != null && path.toString().endsWith(".jar")) {
                        jarChanged = true;
                    }
                }
                key.reset();

                if (jarChanged) {
                    long now = System.currentTimeMillis();
                    // Debounce: ignore events within 500ms of last trigger
                    if (now - lastTrigger > 500) {
                        lastTrigger = now;
                        // Small delay to let file writes complete
                        try { Thread.sleep(500); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        try {
                            onChange.run();
                        } catch (Exception e) {
                            log.error("Callback error", e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Watch service error", e);
            }
        }
        running.set(false);
    }
}
