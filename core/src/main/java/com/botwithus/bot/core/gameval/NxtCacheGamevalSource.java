package com.botwithus.bot.core.gameval;

import com.botwithus.bot.core.cache.CacheSource;
import com.botwithus.bot.core.cache.CacheSourceResolver;
import com.botwithus.bot.core.cache.GamevalIndexVersion;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.util.NativeCache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link GamevalCacheSource} over a private {@link NXTCache} handle on the
 * player's <b>local</b> game cache.
 *
 * <p>Private, and opened <b>without</b> the live-JS5 fallback, for two reasons.
 * The host's shared handle has the fallback on before anything probes it, so its
 * index 67 could come from the network rather than the client the player runs —
 * and the names must match that client. And a build takes seconds; on the shared
 * handle it would hold that handle's lock and stall every config-type lookup for
 * the duration. Calls on different handles may run concurrently.</p>
 */
public final class NxtCacheGamevalSource implements GamevalCacheSource {

    private final NXTCache cache;

    private NxtCacheGamevalSource(NXTCache cache) {
        this.cache = cache;
    }

    /**
     * Opener that resolves the cache directory as the host does, asking the client
     * running as {@code clientPid} first.
     */
    public static GamevalSourceOpener forHost(long clientPid) {
        return () -> open(() -> new CacheSourceResolver(clientPid).resolve());
    }

    /** Opener over an already-chosen resolver; what {@link #forHost(long)} delegates to. */
    public static GamevalSourceOpener forResolver(Supplier<CacheSource> resolve) {
        Objects.requireNonNull(resolve, "resolve");
        return () -> open(resolve);
    }

    private static GamevalSourceOpener.Opening open(Supplier<CacheSource> resolve) {
        String missing = missingBuilder();
        if (missing != null) {
            return new GamevalSourceOpener.Opening.Unavailable(missing);
        }
        return switch (resolve.get()) {
            case CacheSource.Live live -> new GamevalSourceOpener.Opening.Unavailable(
                    "no local game cache was found, and a local gameval build reads only the "
                            + "local cache");
            case CacheSource.LocalDirectory local -> openLocal(local.directory());
        };
    }

    /** Why the loaded DLL cannot build, or null when it can. Touches NXTCache only if it exists. */
    private static String missingBuilder() {
        if (NativeCache.locateNxtCacheDll().isEmpty()) {
            return "no " + NativeCache.NXTCACHE_DLL_NAME + " is deployed";
        }
        try {
            return NXTCache.supportsGamevalBuild() ? null
                    : "this " + NativeCache.NXTCACHE_DLL_NAME + " predates nxt_build_gameval_db";
        } catch (LinkageError e) {
            return NativeCache.NXTCACHE_DLL_NAME + " could not be loaded (" + e + ")";
        }
    }

    private static GamevalSourceOpener.Opening openLocal(Path directory) {
        try {
            return new GamevalSourceOpener.Opening.Opened(
                    new NxtCacheGamevalSource(NXTCache.openLocal(directory)));
        } catch (IOException | RuntimeException e) {
            return new GamevalSourceOpener.Opening.Unavailable(
                    "the local game cache at " + directory + " would not open (" + e.getMessage() + ")");
        }
    }

    @Override
    public GamevalIndexVersion probe() {
        return cache.gamevalIndexVersion();
    }

    @Override
    public void build(Path out) {
        cache.buildGamevalDb(out);
    }

    @Override
    public void close() {
        cache.close();
    }
}
