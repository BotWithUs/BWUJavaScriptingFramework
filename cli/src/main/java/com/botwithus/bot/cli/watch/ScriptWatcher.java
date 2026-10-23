package com.botwithus.bot.cli.watch;

import com.botwithus.bot.core.runtime.ManagementScriptLoader;
import com.botwithus.bot.core.runtime.ScriptFolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches {@code scripts/} and {@code scripts/management/} for JARs being
 * added, rebuilt or deleted, and reports which of the two folders changed.
 *
 * <p>A burst of changes (a build writing several JARs, or one JAR written in
 * pieces) is reported once: after the first change the watcher waits a short
 * settle delay, takes every change that arrived meanwhile, and calls back with
 * the set of folders touched. The management folder is watched from the moment
 * it exists, including when it is created after the watch started.</p>
 *
 * <p>The callback runs on the watcher's own virtual thread. {@link #stop()}
 * closes the watch rather than interrupting that thread, so a reload the
 * callback is running is never cut short.</p>
 */
public final class ScriptWatcher {

    /** Pause after the first change of a burst, so a partly written JAR can finish. */
    static final Duration DEFAULT_SETTLE = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(ScriptWatcher.class);
    private static final String JAR_SUFFIX = ".jar";

    private final Path scriptsDir;
    private final Path managementDir;
    private final Consumer<Set<ScriptFolder>> onChange;
    private final Duration settle;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<WatchKey, ScriptFolder> folderByKey = new ConcurrentHashMap<>();
    private volatile WatchService service;

    public ScriptWatcher(Path scriptsDir, Consumer<Set<ScriptFolder>> onChange) {
        this(scriptsDir, onChange, DEFAULT_SETTLE);
    }

    /** @param settle how long to wait after the first change of a burst; short in tests */
    ScriptWatcher(Path scriptsDir, Consumer<Set<ScriptFolder>> onChange, Duration settle) {
        this.scriptsDir = scriptsDir;
        this.managementDir = ManagementScriptLoader.managementDirIn(scriptsDir);
        this.onChange = onChange;
        this.settle = settle;
    }

    /**
     * Starts watching. The folders are registered before this returns, so a
     * change made after it returns is seen. Returns {@code false} when the
     * watch could not be set up (logged). One watch per instance: a second
     * call only reports whether the first is still running, so after
     * {@link #stop()} create a new watcher.
     */
    public boolean start() {
        if (!started.compareAndSet(false, true)) {
            return running.get();
        }
        running.set(true);
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            service = ws;
            register(ws, scriptsDir, ScriptFolder.SCRIPTS);
            if (Files.isDirectory(managementDir)) {
                register(ws, managementDir, ScriptFolder.MANAGEMENT);
            }
            Thread.ofVirtual().name("script-watcher").start(() -> watchLoop(ws));
            return true;
        } catch (IOException e) {
            log.error("Could not watch {}: {}", scriptsDir, e.getMessage());
            stop();
            return false;
        }
    }

    /** Stops watching. A callback already running finishes; no further one starts. */
    public void stop() {
        running.set(false);
        WatchService ws = service;
        if (ws != null) {
            closeQuietly(ws);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void watchLoop(WatchService ws) {
        try {
            while (running.get()) {
                Set<ScriptFolder> changed = EnumSet.noneOf(ScriptFolder.class);
                collect(ws, ws.take(), changed);
                if (changed.isEmpty()) {
                    continue;
                }
                Thread.sleep(settle);
                drainPending(ws, changed);
                if (running.get()) {
                    fire(changed);
                }
            }
        } catch (ClosedWatchServiceException e) {
            log.debug("Script watch closed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            closeQuietly(ws);
        }
    }

    /** Folds in every change already queued, without waiting for more. */
    private void drainPending(WatchService ws, Set<ScriptFolder> changed) {
        WatchKey key = ws.poll();
        while (key != null) {
            collect(ws, key, changed);
            key = ws.poll();
        }
    }

    private void collect(WatchService ws, WatchKey key, Set<ScriptFolder> changed) {
        ScriptFolder folder = folderByKey.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW || folder == null) {
                changed.addAll(EnumSet.allOf(ScriptFolder.class));
                continue;
            }
            // The context of an ENTRY_* event is the file name; toString avoids
            // a cast from the wildcard WatchEvent<?>.
            String name = String.valueOf(event.context());
            if (folder == ScriptFolder.SCRIPTS && name.equals(managementDir.getFileName().toString())) {
                onManagementFolderEvent(ws, event, changed);
            } else if (name.endsWith(JAR_SUFFIX)) {
                changed.add(folder);
            }
        }
        if (!key.reset()) {
            folderByKey.remove(key);
        }
    }

    /**
     * An event on the management folder's own entry. Creating or removing it is
     * a change, and a new folder is watched from then on. A modify is not: on
     * Windows it only echoes a change inside the folder, which the folder's own
     * watch reports.
     */
    private void onManagementFolderEvent(WatchService ws, WatchEvent<?> event, Set<ScriptFolder> changed) {
        if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
            return;
        }
        changed.add(ScriptFolder.MANAGEMENT);
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(managementDir)) {
            try {
                register(ws, managementDir, ScriptFolder.MANAGEMENT);
            } catch (IOException e) {
                log.warn("Could not watch {}: {}", managementDir, e.getMessage());
            }
        }
    }

    private void register(WatchService ws, Path dir, ScriptFolder folder) throws IOException {
        WatchKey key = dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        folderByKey.put(key, folder);
    }

    private void fire(Set<ScriptFolder> changed) {
        try {
            onChange.accept(Set.copyOf(changed));
        } catch (RuntimeException e) {
            log.error("Script watcher callback failed", e);
        }
    }

    private static void closeQuietly(WatchService ws) {
        try {
            ws.close();
        } catch (IOException e) {
            log.debug("Closing the script watch failed: {}", e.getMessage());
        }
    }
}
