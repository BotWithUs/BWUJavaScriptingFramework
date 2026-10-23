package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.core.runtime.SdnDelivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreRestorerTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String ID = "42";
    private static final String OTHER_ID = "43";
    private static final int BUILD = 3;
    private static final int RACING_CALLERS = 16;
    private static final long RACE_TIMEOUT_SECONDS = 30;

    @TempDir
    Path home;

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final List<Runnable> queued = new CopyOnWriteArrayList<>();
    private final AtomicInteger launcherCalls = new AtomicInteger();
    private final List<BotScript> restored = new CopyOnWriteArrayList<>();
    private final StoreShelf shelf = new StoreShelf();

    public static final class Restored implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    private static SdnCatalogueEntry entry(String id) {
        return new SdnCatalogueEntry(id, "Restored", "author", "me", "1.0", "2", "", "",
                Restored.class.getName(), false, true, true, false, null, null, BUILD);
    }

    private InstalledScriptsLedger ledgerWith(String... ids) throws IOException {
        InstalledScriptsLedger ledger = new InstalledScriptsLedger(home, InstantSource.fixed(NOW));
        for (String id : ids) {
            ledger.record(Map.of("com.example.Class" + id, entry(id)));
        }
        return ledger;
    }

    /** A launcher that delivers one jar per entry asked for, pairing each with its request. */
    private SdnInstallResult deliverEach(List<SdnCatalogueEntry> entries) {
        launcherCalls.incrementAndGet();
        Map<String, List<BotScript>> byId = new HashMap<>();
        List<BotScript> scripts = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            BotScript script = new Restored();
            SdnDelivery.Jar jar = new SdnDelivery.Jar(i, List.of(script), List.of(Restored::new));
            shelf.stock(Optional.of(entries.get(i).id()), jar);
            byId.put(entries.get(i).id(), List.of(script));
            scripts.add(script);
        }
        return new SdnInstallResult.Installed(scripts, byId);
    }

    private SdnInstallResult refuse(List<SdnCatalogueEntry> entries) {
        launcherCalls.incrementAndGet();
        return new SdnInstallResult.Failed("sdn_not_delivered");
    }

    private StoreRestorer restorer(InstalledScriptsLedger ledger, List<SdnCatalogueEntry> catalogue,
                                   Function<List<SdnCatalogueEntry>, SdnInstallResult> launcher) {
        return new StoreRestorer(new StoreRestorer.Deps(ledger, shelf,
                () -> Optional.of(new SdnCatalogueResult.Delivered(catalogue, false)), () -> true, launcher,
                restored::addAll, queued::add, now::get));
    }

    private void runQueued() {
        List<Runnable> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    /** Fails on a restorer without single-flight: every caller would queue its own restore. */
    @Test
    @Timeout(RACE_TIMEOUT_SECONDS)
    void manyCallersAtOnce_putOneRestoreInFlight() throws Exception {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::deliverEach);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < RACING_CALLERS; i++) {
            callers.add(Thread.ofVirtual().start(() -> {
                awaitQuietly(go);
                restorer.request();
            }));
        }
        go.countDown();
        for (Thread caller : callers) {
            caller.join();
        }

        assertEquals(1, queued.size(), "one restore in flight however many clients connect");
        runQueued();
        restorer.request();
        assertEquals(1, launcherCalls.get(), "nothing is left to restore after it, so nothing more is asked");
        assertEquals(1, restored.size());
        assertTrue(restorer.pending().isEmpty());
    }

    @Test
    void aFailedRestore_waitsBeforeTheNextAndTheWaitGrows() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::refuse);
        restorer.request();
        runQueued();
        assertEquals(Optional.of("sdn_not_delivered"), restorer.lastFailure());

        now.set(NOW.plus(StoreRestorer.RETRY_AFTER).minusSeconds(1));
        restorer.request();
        assertTrue(queued.isEmpty(), "too soon after a failure");

        now.set(NOW.plus(StoreRestorer.RETRY_AFTER));
        restorer.request();
        runQueued();
        assertEquals(2, launcherCalls.get());

        Instant secondFailure = now.get();
        now.set(secondFailure.plus(StoreRestorer.RETRY_AFTER));
        restorer.request();
        assertTrue(queued.isEmpty(), "a second failure in a row doubles the wait");
        now.set(secondFailure.plus(StoreRestorer.RETRY_AFTER.multipliedBy(2)));
        restorer.request();
        assertEquals(1, queued.size());
    }

    @Test
    void retryWait_neverExceedsTheCap() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::refuse);
        Duration step = StoreRestorer.MAX_RETRY_AFTER;
        int failures = 0;
        for (int i = 0; i < RACING_CALLERS; i++) {
            restorer.request();
            failures += queued.size();
            runQueued();
            now.set(now.get().plus(step));
        }
        assertEquals(RACING_CALLERS, failures, "once the wait is at its cap, every cap-length gap retries");
    }

    /** Fails if an id the catalogue dropped stays pending: auto-start would wait on it forever. */
    @Test
    void anIdTheCatalogueNoLongerLists_isSetAsideWithItsReasonAndNeverAskedFor() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID, OTHER_ID), List.of(entry(ID)), this::deliverEach);

        restorer.request();
        runQueued();

        assertEquals(Map.of(OTHER_ID, StoreRestorer.NOT_IN_CATALOGUE), restorer.setAside());
        assertTrue(restorer.pending().isEmpty());
        assertFalse(restorer.isAwaited());
        restorer.request();
        assertEquals(1, launcherCalls.get(), "the dropped id is never requested");
    }

    /** Fails if an unpairable delivery is fetched again on every tick. */
    @Test
    void aDeliveryWithNothingForAnId_setsItAsideRatherThanFetchingAgain() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), entries -> {
            launcherCalls.incrementAndGet();
            return new SdnInstallResult.Installed(List.of(), Map.of());
        });

        restorer.request();
        runQueued();
        restorer.request();

        assertEquals(Map.of(ID, StoreRestorer.NOT_DELIVERED), restorer.setAside());
        assertTrue(queued.isEmpty());
        assertEquals(1, launcherCalls.get());
    }

    @Test
    void waiters_runWhenTheRestoreSettles_andAreRefusedOnceNothingIsAwaited() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::refuse);
        AtomicBoolean ran = new AtomicBoolean();

        assertTrue(restorer.whenSettled(() -> ran.set(true)), "nothing tried yet, so a restore is still to come");
        restorer.request();
        assertFalse(ran.get());
        runQueued();

        assertTrue(ran.get());
        assertFalse(restorer.whenSettled(() -> { }), "after a failure nothing is awaited; the caller reports");
    }

    @Test
    void withNoCatalogueYet_nothingIsAskedButTheRestoreIsStillAwaited() throws IOException {
        StoreRestorer restorer = new StoreRestorer(new StoreRestorer.Deps(ledgerWith(ID), shelf, Optional::empty,
                () -> true, this::deliverEach, restored::addAll, queued::add, now::get));

        restorer.request();

        assertTrue(queued.isEmpty());
        assertTrue(restorer.isAwaited());
    }

    @Test
    void withDeliveryOff_itSaysSoAndNothingIsAwaited() throws IOException {
        StoreRestorer restorer = new StoreRestorer(new StoreRestorer.Deps(ledgerWith(ID), shelf,
                () -> Optional.of(new SdnCatalogueResult.Delivered(List.of(entry(ID)), false)), () -> false,
                this::deliverEach, restored::addAll, queued::add, now::get));

        restorer.request();

        assertEquals(Optional.of("this host was not started by the launcher"), restorer.lastFailure());
        assertFalse(restorer.isAwaited());
        assertEquals(0, launcherCalls.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
