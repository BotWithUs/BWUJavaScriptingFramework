package com.botwithus.bot.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds {@link URLClassLoader} instances created by a previous module-load
 * pass so they can be closed before a reload. On Windows, leaving the
 * old classloaders open keeps file handles on the underlying JARs and
 * blocks the next load from re-reading them.
 *
 * <p>Each script loader owns its own tracker — there is no cross-loader
 * shared state. The tracker is mutated only by the loader that owns it,
 * and all access is single-threaded (the loader's {@code loadScripts}
 * call sites).</p>
 */
final class PreviousLoaderTracker {

    private static final Logger log = LoggerFactory.getLogger(PreviousLoaderTracker.class);

    private final List<URLClassLoader> loaders = new ArrayList<>();

    /** Adds a loader to be closed on the next {@link #closeAll()}. */
    void add(URLClassLoader loader) {
        loaders.add(loader);
    }

    /** Closes every tracked loader and clears the list. */
    void closeAll() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.error("Failed to close previous classloader: {}", e.getMessage());
            }
        }
        loaders.clear();
    }
}
