package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.core.util.NativeCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real NXTCache.dll against the machine's local game cache, driven from many threads.
 * Skips when either is absent (CI has neither).
 *
 * <p>The guard's {@code onEnter} hook counts every entry into the lock. The count equals
 * the number of getter calls, which shows that each getter really goes through the guard,
 * rather than merely that the run happened not to crash.</p>
 */
class NXTCacheConcurrencyLiveTest {

    private static final int COINS_ITEM_ID = 995;
    /** A varbit that exists in current caches (a quest progress varbit). */
    private static final int KNOWN_VARBIT_ID = 9363;
    private static final int THREADS = 8;
    private static final int ROUNDS = 50;
    private static final long WAIT_SECONDS = 60;

    private static Path cacheDirectory;

    @BeforeAll
    static void requireTheLibraryAndALocalCache() {
        assumeTrue(NativeCache.locateNxtCacheDll().isPresent(), "no NXTCache.dll deployed");
        switch (new CacheSourceResolver().resolve()) {
            case CacheSource.LocalDirectory local -> cacheDirectory = local.directory();
            case CacheSource.Live ignored -> assumeTrue(false, "no local game cache found");
        }
    }

    @Test
    void twoGettersHammeredFromManyThreads_allGoThroughTheLock_andAgreeWithASingleThread()
            throws IOException, InterruptedException {
        AtomicInteger entries = new AtomicInteger();
        try (NXTCache cache = NXTCache.openLocal(cacheDirectory,
                new HandleGuard(entries::incrementAndGet))) {
            String coins = cache.getItem(COINS_ITEM_ID).name();
            VarbitType varbit = cache.getVarbit(KNOWN_VARBIT_ID);
            int baselineEntries = entries.get();

            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            runOnThreads(() -> {
                for (int i = 0; i < ROUNDS; i++) {
                    ItemType item = cache.getItem(COINS_ITEM_ID);
                    VarbitType vb = cache.getVarbit(KNOWN_VARBIT_ID);
                    if (!coins.equals(item.name()) || !varbit.equals(vb)) {
                        failures.add(new AssertionError("a concurrent read disagreed"));
                    }
                }
            }, failures);

            assertEquals(List.of(), List.copyOf(failures));
            assertEquals(baselineEntries + THREADS * ROUNDS * 2, entries.get(),
                    "every getter call entered the guard exactly once");
        }
    }

    @Test
    void closeRacingCalls_neverReachesAFreedHandle_andLaterCallsAreRefused()
            throws IOException, InterruptedException {
        NXTCache cache = NXTCache.openLocal(cacheDirectory, new HandleGuard());
        CountDownLatch everyThreadHasRead = new CountDownLatch(THREADS);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            threads.add(Thread.ofPlatform().start(() -> readUntilClosed(cache, everyThreadHasRead, failures)));
        }
        assertTrue(everyThreadHasRead.await(WAIT_SECONDS, TimeUnit.SECONDS));

        cache.close();

        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive(), "a reader never saw the close");
        }
        assertEquals(List.of(), List.copyOf(failures), "only IllegalStateException may follow a close");
        assertThrows(IllegalStateException.class, () -> cache.getItem(COINS_ITEM_ID));
    }

    /** Reads until the close refuses it; anything but that refusal is a failure. */
    private static void readUntilClosed(NXTCache cache, CountDownLatch hasRead,
                                        ConcurrentLinkedQueue<Throwable> failures) {
        boolean counted = false;
        while (true) {
            try {
                cache.getItem(COINS_ITEM_ID);
                if (!counted) {
                    hasRead.countDown();
                    counted = true;
                }
            } catch (IllegalStateException closed) {
                return;
            } catch (RuntimeException other) {
                failures.add(other);
                return;
            }
        }
    }

    private static void runOnThreads(Runnable body, ConcurrentLinkedQueue<Throwable> failures)
            throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    body.run();
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive(), "a reader did not finish");
        }
    }
}
