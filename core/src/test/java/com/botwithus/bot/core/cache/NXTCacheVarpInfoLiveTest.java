package com.botwithus.bot.core.cache;

import com.botwithus.bot.core.util.NativeCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code nxt_get_varp_info} through the real NXTCache.dll and the machine's local game cache.
 * Skips when the DLL predates the export (the deployed one may; point {@code -Dnxtcache.dll}
 * at a newer build) or when there is no local cache.
 *
 * <p>The ids are chosen for the rules they cover: an INT varp (default 0), a BOOLEAN varp
 * without opcode 7 (the domain default -1), a LONG varp (default -1), and ids that are no
 * varp at all.</p>
 */
class NXTCacheVarpInfoLiveTest {

    private static final int INT_VARP = 13537;
    private static final int BOOLEAN_DOMAIN_VARP = 97;
    private static final int LONG_VARP = 12921;
    private static final int NOT_A_VARP = 20000;
    private static final int NEGATIVE_ID = -5;
    private static final long WARMUP_WAIT_SECONDS = 60;
    private static final long POLL_MILLIS = 10;

    private static Path cacheDirectory;

    @BeforeAll
    static void requireAVarpCapableLibraryAndALocalCache() {
        assumeTrue(NativeCache.locateNxtCacheDll().isPresent(), "no NXTCache.dll deployed");
        assumeTrue(NXTCache.supportsVarpInfo(), "this NXTCache.dll has no nxt_get_varp_info");
        switch (new CacheSourceResolver().resolve()) {
            case CacheSource.LocalDirectory local -> cacheDirectory = local.directory();
            case CacheSource.Live ignored -> assumeTrue(false, "no local game cache found");
        }
    }

    @Test
    void beforeTheWarmup_everyAnswerIsUnknown_andAfterItTheCacheIsConsulted()
            throws IOException, InterruptedException {
        try (NXTCache cache = NXTCache.openLocal(cacheDirectory, new HandleGuard())) {
            assertEquals(new VarpCacheInfo.Unknown(), cache.varpInfo(INT_VARP),
                    "no read may reach the cache before the warm-up has loaded it");

            cache.startVarpInfoWarmup();
            awaitWarm(cache);

            assertEquals(new VarpCacheInfo.Default(0), cache.varpInfo(INT_VARP));
            assertEquals(new VarpCacheInfo.Default(-1), cache.varpInfo(BOOLEAN_DOMAIN_VARP),
                    "BOOLEAN with opcode 7 absent takes the domain default -1");
            assertEquals(new VarpCacheInfo.Default(-1), cache.varpInfo(LONG_VARP));
            assertEquals(new VarpCacheInfo.NoSuchVarp(), cache.varpInfo(NOT_A_VARP));
            assertEquals(new VarpCacheInfo.NoSuchVarp(), cache.varpInfo(NEGATIVE_ID));
        }
    }

    @Test
    void aDefinitiveAnswerIsMemoised_soRepeatReadsNeverReachTheHandle()
            throws IOException, InterruptedException {
        AtomicInteger entries = new AtomicInteger();
        try (NXTCache cache = NXTCache.openLocal(cacheDirectory,
                new HandleGuard(entries::incrementAndGet))) {
            cache.startVarpInfoWarmup();
            awaitWarm(cache);
            cache.varpInfo(INT_VARP);
            cache.varpInfo(NOT_A_VARP);
            int afterFirst = entries.get();

            cache.varpInfo(INT_VARP);
            cache.varpInfo(NOT_A_VARP);

            assertEquals(afterFirst, entries.get(), "found and not-found are both memoised");
        }
    }

    private static void awaitWarm(NXTCache cache) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WARMUP_WAIT_SECONDS);
        while (!cache.isVarpInfoWarm()) {
            assertTrue(System.nanoTime() < deadline, "the varp warm-up never finished");
            Thread.sleep(POLL_MILLIS);
        }
    }
}
