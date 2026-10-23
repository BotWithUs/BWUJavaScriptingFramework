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
import java.util.Set;
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
        return new SdnCatalogueEntry(id, "Restored " + id, "author", "me", "1.0", "2", "", "",
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
        assertEquals(Optional.of("Restored " + ID + ": sdn_not_delivered"), restorer.lastFailure());

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

    // ── The review's fixes ──────────────────────────────────────────────────

    /** Delivers every entry asked for except {@code refusedId}, which it refuses. */
    private Function<List<SdnCatalogueEntry>, SdnInstallResult> refusing(String refusedId) {
        return entries -> entries.getFirst().id().equals(refusedId) ? refuse(entries) : deliverEach(entries);
    }

    /** Fails twice, so the wait ({@link StoreRestorer#RETRY_AFTER} doubled) outlasts the nudge floor. */
    private StoreRestorer failedTwice() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::refuse);
        restorer.request();
        runQueued();
        now.set(now.get().plus(StoreRestorer.RETRY_AFTER));
        restorer.request();
        runQueued();
        return restorer;
    }

    /**
     * S1: after a failure a missing game client explains, a client connecting retries
     * before the wait is over, once per failure, but never within the floor of the
     * last attempt. Fails if the nudge is just a request.
     */
    @Test
    void aNudge_afterALinkFailure_retriesOnceBeforeTheWaitIsOver() throws IOException {
        StoreRestorer restorer = failedTwice();
        now.set(now.get().plus(StoreRestorer.RETRY_AFTER));

        restorer.request();
        assertTrue(queued.isEmpty(), "the timer still waits: the second failure doubled the wait");
        restorer.nudge();
        restorer.nudge();
        assertEquals(1, queued.size(), "one nudge retries; the second finds it running");
    }

    /**
     * B3: a client reconnecting in a loop cannot drive more than one restore per
     * {@link StoreRestorer#RETRY_AFTER}, however many times it connects.
     */
    @Test
    void aReconnectLoop_drivesAtMostOneRestorePerFloor() throws IOException {
        StoreRestorer restorer = failedTwice();
        int attempts = 0;
        for (int second = 0; second < 2 * StoreRestorer.RETRY_AFTER.toSeconds(); second++) {
            restorer.nudge();
            attempts += queued.size();
            runQueued();
            now.set(now.get().plusSeconds(1));
        }
        assertEquals(1, attempts, "one attempt in the first floor after the failure, then the floor holds again");
    }

    /** A failure no client explains, such as a script that threw, is not retried early. */
    @Test
    void aNudge_afterAFailureNoClientExplains_waits() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), entries -> {
            throw new IllegalStateException("disk full");
        });
        restorer.request();
        runQueued();

        restorer.nudge();

        assertTrue(queued.isEmpty());
    }

    /** S2: each id is asked for on its own, so one the launcher refuses costs only itself. */
    @Test
    void oneRefusedId_doesNotStopTheOthers_andTheFailureNamesIt() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID, OTHER_ID), List.of(entry(ID), entry(OTHER_ID)),
                refusing(OTHER_ID));

        restorer.request();
        runQueued();

        assertEquals(Set.of(OTHER_ID), restorer.pending());
        assertEquals(1, restored.size());
        assertTrue(restorer.lastFailure().orElseThrow().contains("Restored " + OTHER_ID), restorer.lastFailure()::get);
    }

    /** S2: once the launcher has shown it can deliver, an id refused K times in a row is set aside. */
    @Test
    void anIdRefusedKTimesInARow_isSetAside_onceTheLauncherHasDelivered() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID, OTHER_ID), List.of(entry(ID), entry(OTHER_ID)),
                refusing(OTHER_ID));
        for (int run = 0; run < StoreRestorer.REFUSALS_BEFORE_SET_ASIDE; run++) {
            restorer.request();
            runQueued();
            now.set(now.get().plus(StoreRestorer.MAX_RETRY_AFTER));
        }

        assertTrue(restorer.setAside().containsKey(OTHER_ID));
        assertTrue(restorer.pending().isEmpty());
        restorer.request();
        assertTrue(queued.isEmpty(), "never asked for again this run");
    }

    /** S2: with nothing ever delivered, refusals are not counted: a launcher with no link refuses all alike. */
    @Test
    void refusalsAreNotCounted_whileTheLauncherHasDeliveredNothing() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), this::refuse);
        for (int run = 0; run <= StoreRestorer.REFUSALS_BEFORE_SET_ASIDE; run++) {
            restorer.request();
            runQueued();
            now.set(now.get().plus(StoreRestorer.MAX_RETRY_AFTER));
        }

        assertTrue(restorer.setAside().isEmpty());
        assertEquals(Set.of(ID), restorer.pending());
    }

    /** S3: a stale catalogue may predate the purchase, so it sets nothing aside. */
    @Test
    void aStaleCatalogue_setsNothingAside() throws IOException {
        StoreRestorer restorer = new StoreRestorer(new StoreRestorer.Deps(ledgerWith(ID, OTHER_ID), shelf,
                () -> Optional.of(new SdnCatalogueResult.Delivered(List.of(entry(ID)), true)), () -> true,
                this::deliverEach, restored::addAll, queued::add, now::get));

        restorer.request();
        runQueued();

        assertTrue(restorer.setAside().isEmpty());
        assertEquals(Set.of(OTHER_ID), restorer.pending());
    }

    /** S4: an Error from the install still settles the restore, so nothing is left waiting forever. */
    @Test
    void anErrorFromTheInstall_stillSettles() throws IOException {
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)), entries -> {
            throw new NoClassDefFoundError("com/example/Missing");
        });
        AtomicBoolean settled = new AtomicBoolean();
        restorer.whenSettled(() -> settled.set(true));

        restorer.request();
        runQueued();

        assertTrue(settled.get());
        assertFalse(restorer.isAwaited());
        assertTrue(restorer.lastFailure().orElseThrow().contains("com/example/Missing"));
    }

    /** S1: a caller told the restore failed is still called back when a later one delivers. */
    @Test
    void whenRestored_waitsThroughFailures_andRunsOnTheFirstRestoreThatDelivers() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        StoreRestorer restorer = restorer(ledgerWith(ID), List.of(entry(ID)),
                entries -> calls.getAndIncrement() == 0 ? refuse(entries) : deliverEach(entries));
        AtomicBoolean restoredCalled = new AtomicBoolean();
        restorer.whenRestored(() -> restoredCalled.set(true));

        restorer.request();
        runQueued();
        assertFalse(restoredCalled.get(), "a failed restore does not call it");
        now.set(now.get().plus(StoreRestorer.RETRY_AFTER));
        restorer.request();
        runQueued();

        assertTrue(restoredCalled.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
