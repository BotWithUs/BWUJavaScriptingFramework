package com.botwithus.bot.core.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the on-disk location of cached native artifacts. The
 * canonical cache root is {@code $USER_HOME/.botwithus/native/}, matching
 * the convention used by other persistent stores in this codebase
 * (credentials, repository config, trusted PGP keys).
 *
 * <p>Construction is side-effect free; {@link #ensureExists()} creates
 * the directory on demand. Callers that only read the path can use
 * {@link #cacheDir()} or {@link #resolve(String)} directly without
 * creating the directory; callers that intend to write into it should
 * call {@link #ensureExists()} first.</p>
 *
 * <p>Cache population is handled by the loader DLL, not Java — this
 * class only provides the path convention.</p>
 */
public final class NativeCache {

    private static final String CONFIG_DIR_NAME = ".botwithus";
    private static final String NATIVE_SUBDIR = "native";

    /** File name of the NXT cache decoder library within the native cache. */
    public static final String NXTCACHE_DLL_NAME = "NXTCache.dll";

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

    /**
     * Locate {@code NXTCache.dll} on disk without loading it, using the same
     * precedence as {@code core.cache.NXTCache} at link time: the
     * {@code -Dnxtcache.dll} override first (when it points at an existing
     * file), then the downloaded native-cache entry under the default cache
     * root. Returns empty when neither is present yet — e.g. while the
     * loader's native-artifacts download is still in flight.
     *
     * <p>Unlike {@code NXTCache}'s own resolver this neither links the DLL nor
     * throws when it is absent, so UI code can poll it as a readiness gate
     * before triggering the eager link.</p>
     */
    public static Optional<Path> locateNxtCacheDll() {
        String override = System.getProperty("nxtcache.dll");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (Files.isRegularFile(overridePath)) {
                return Optional.of(overridePath);
            }
        }
        Path cached = new NativeCache().resolve(NXTCACHE_DLL_NAME);
        return Files.isRegularFile(cached) ? Optional.of(cached) : Optional.empty();
    }
}
