package com.botwithus.bot.core.loader.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves the on-disk location of cached native artifacts. The
 * canonical cache root is {@code $USER_HOME/.botwithus/native/}, matching
 * the convention used by other persistent stores in this codebase
 * (credentials, repository config, trusted PGP keys).
 *
 * <p>Construction is side-effect free; {@link #ensureExists()} creates
 * the directory on demand. Callers that only read the path (the BwuClient
 * resolver fallback) can use {@link #cacheDir()} directly without
 * creating the directory; callers that intend to write into it should
 * call {@link #ensureExists()} first.</p>
 */
public final class NativeCache {

    private static final String CONFIG_DIR_NAME = ".botwithus";
    private static final String NATIVE_SUBDIR = "native";

    private final Path cacheDir;

    /** Cache rooted at the user's home directory ({@code ~/.botwithus/native/}). */
    public NativeCache() {
        this(Path.of(System.getProperty("user.home")).resolve(CONFIG_DIR_NAME).resolve(NATIVE_SUBDIR));
    }

    /** Cache rooted at an explicit directory; intended for tests. */
    public NativeCache(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /** Absolute path of the cache directory. Not guaranteed to exist; see {@link #ensureExists()}. */
    public Path cacheDir() {
        return cacheDir;
    }

    /** Path of an entry within the cache directory. Existence is not checked. */
    public Path resolve(String filename) {
        Objects.requireNonNull(filename, "filename");
        return cacheDir.resolve(filename);
    }

    /** Create the cache directory if it does not yet exist. */
    public Path ensureExists() throws IOException {
        Files.createDirectories(cacheDir);
        return cacheDir;
    }
}
