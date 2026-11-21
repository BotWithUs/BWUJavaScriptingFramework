package com.botwithus.bot.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Turns a decided {@link CacheSource} into an open cache. This is the only
 * policy between the resolver and the native library, and its only native
 * verb is a <b>local</b> open: there is no path from here to the live JS5
 * service or to a live fallback.
 *
 * <p>It is generic over the handle type and takes the local open as a
 * function, so the policy is testable with a spy in place of
 * {@code NXTCache.dll}: {@link NXTCache#open(CacheSource)} is this with
 * {@link NXTCache#openLocal(Path)} plugged in.</p>
 */
public final class CacheOpening {

    private static final Logger log = LoggerFactory.getLogger(CacheOpening.class);

    private CacheOpening() {
    }

    /**
     * Opens a cache directory read-only and local-only.
     *
     * @param <T> the handle type
     */
    @FunctionalInterface
    public interface LocalOpener<T> {
        /** Opens the local cache at {@code directory}. */
        T openLocal(Path directory) throws IOException;
    }

    /**
     * Opens {@code source} through {@code opener}, logging which source was taken.
     *
     * @throws NoLocalCacheException when the source is {@link CacheSource.Unavailable};
     *         the opener is not called
     * @throws IOException           when the local open itself fails
     */
    public static <T> T open(CacheSource source, LocalOpener<T> opener) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(opener, "opener");
        return switch (source) {
            case CacheSource.LocalDirectory local -> openLocal(local, opener);
            case CacheSource.Unavailable unavailable -> throw refuse(unavailable);
        };
    }

    private static <T> T openLocal(CacheSource.LocalDirectory local, LocalOpener<T> opener)
            throws IOException {
        T cache = opener.openLocal(local.directory());
        if (local.isExplicit()) {
            log.info("NXTCache: local cache {} named by -D{} (local only)",
                    local.directory(), CacheSourceResolver.PATH_PROPERTY);
        } else {
            log.info("NXTCache: discovered the client's local cache at {} (local only)",
                    local.directory());
        }
        return cache;
    }

    private static NoLocalCacheException refuse(CacheSource.Unavailable unavailable) {
        log.warn("NXTCache: {}. Config-type lookups will fail; the host never reads the cache "
                + "over the network.", unavailable.reason());
        return new NoLocalCacheException(unavailable.reason());
    }
}
