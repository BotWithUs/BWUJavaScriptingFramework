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
 * pass so they can be closed before a reload, releasing whatever each one
 * opened on its own account.
 *
 * <p>Closing them does <em>not</em> release the script JARs, and never did.
 * These loaders are only the <em>parent</em> of the loader a child
 * {@code ModuleLayer} defines; the JAR handle belongs to that inner loader's
 * module reader, which is unreachable and uncloseable. Keeping the scripts
 * directory rebuildable is {@link ScriptJarStaging}'s job — this class cannot
 * do it and should not be asked to.</p>
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
     * <p>This deliberately leaks the loader for the life of the process, because
     * closing it out from under a live thread gives that thread
     * {@code NoClassDefFoundError} on its next class load. The staged JAR it
     * was defined from leaks with it, which is affordable precisely because it
     * is a copy: the scripter's own JAR is never the file left open.</p>
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
