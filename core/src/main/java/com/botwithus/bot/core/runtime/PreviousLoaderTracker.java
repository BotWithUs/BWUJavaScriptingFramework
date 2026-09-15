package com.botwithus.bot.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds {@link URLClassLoader} instances created by a previous module-load
 * pass so they can be closed before a reload. On Windows, leaving the
 * old classloaders open keeps file handles on the underlying JARs and
 * blocks the next load from re-reading them.
 *
 * <p>Each script loader owns its own tracker — there is no cross-loader
 * shared state. {@link #add} and {@link #closeAll()} are called only by the
 * owning loader's {@code loadScripts} call sites and are single-threaded;
 * {@link #pin} is the exception and is called from the runtime's watchdog
 * thread, so the pinned set is concurrent.</p>
 */
final class PreviousLoaderTracker {

    private static final Logger log = LoggerFactory.getLogger(PreviousLoaderTracker.class);

    private final List<URLClassLoader> loaders = new ArrayList<>();
    /**
     * Loaders belonging to abandoned script threads. Identity-based (ClassLoader
     * doesn't override equals), and never emptied — see {@link #pin}.
     */
    private final Set<ClassLoader> pinned =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Adds a loader to be closed on the next {@link #closeAll()}. */
    void add(URLClassLoader loader) {
        loaders.add(loader);
    }

    /**
     * Marks a loader as never-closable because a script thread it defined is
     * still running and cannot be killed.
     *
     * <p>This deliberately leaks the loader (and its JAR file handle) for the
     * life of the process. That is the lesser evil: closing a loader out from
     * under a live thread gives that thread {@code NoClassDefFoundError} on its
     * next class load, and on Windows the still-running loader keeps the JAR
     * handle open anyway, so {@code close()} can't release it — which wedges
     * every later reload. Leaking one loader costs memory; closing it corrupts
     * the reload path the tracker exists to protect.</p>
     */
    void pin(ClassLoader loader) {
        if (loader != null) {
            pinned.add(loader);
        }
    }

    /** Closes every tracked loader except the pinned ones, and clears the list. */
    void closeAll() {
        for (URLClassLoader loader : loaders) {
            if (pinned.contains(loader)) {
                log.warn("Not closing classloader {}: a script thread it defined is still running",
                        loader);
                continue;
            }
            try {
                loader.close();
            } catch (IOException e) {
                log.error("Failed to close previous classloader: {}", e.getMessage());
            }
        }
        loaders.clear();
    }
}
