package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refresh schedule, the in-flight guard and "a failure never replaces a good list",
 * driven by a hand-advanced clock so no test sleeps.
 */
class SdnCatalogueRefresherTest {

    private static final Instant T0 = Instant.parse("2026-09-21T12:00:00Z");
    /** A jitter draw of 0.5 scales every delay by exactly 1.0. */
    private static final double NO_JITTER = 0.5;
    private static final Duration ONE_MS = Duration.ofMillis(1);
    private static final String SCRIPT_ID = "woodcutter";

    private final ManualClock clock = new ManualClock(T0);

    // ------------------------------------------------------------------ schedule

    @Test
    void tick_beforeAnyFetch_fetchesImmediately() {
        CountingFetch fetch = new CountingFetch(delivered("1.0"));
        SdnCatalogueRefresher refresher = direct(fetch);

        refresher.tick();

        assertEquals(1, fetch.calls());
        assertEquals(Optional.of(delivered("1.0")), refresher.shown());
    }

    @Test
    void tick_afterSuccess_waitsExactlyTheIntervalWithoutJitter() {
        CountingFetch fetch = new CountingFetch(delivered("1.0"));
        SdnCatalogueRefresher refresher = direct(fetch);
        refresher.tick();

        clock.set(T0.plus(SdnCatalogueRefresher.REFRESH_INTERVAL).minus(ONE_MS));
        refresher.tick();
        assertEquals(1, fetch.calls(), "not due one millisecond early");

        clock.set(T0.plus(SdnCatalogueRefresher.REFRESH_INTERVAL));
        refresher.tick();
        assertEquals(2, fetch.calls(), "due at the interval");
    }

    @Test
    void jitter_staysWithinTenPercentOfTheInterval() {
        Duration interval = SdnCatalogueRefresher.REFRESH_INTERVAL;
        assertEquals(T0.plus(interval.multipliedBy(9).dividedBy(10)), nextDueAfterSuccess(0.0));
        assertEquals(T0.plus(interval.multipliedBy(11).dividedBy(10)), nextDueAfterSuccess(1.0));
    }

    @Test
    void anUpdatedScript_showsItsNewVersion_withoutPressingRefresh() {
        ScriptedFetch fetch = new ScriptedFetch(delivered("1.0"), delivered("1.1"));
        SdnCatalogueRefresher refresher = direct(fetch);
        refresher.tick();

        clock.set(T0.plus(SdnCatalogueRefresher.REFRESH_INTERVAL));
        refresher.tick();

        assertEquals("1.1", versionShown(refresher));
    }

    // ------------------------------------------------------------------ backoff

    @Test
    void retryDelay_doublesFromThirtySecondsAndCapsAtTenMinutes() {
        assertEquals(Duration.ofSeconds(30), SdnCatalogueRefresher.retryDelay(1));
        assertEquals(Duration.ofSeconds(60), SdnCatalogueRefresher.retryDelay(2));
        assertEquals(Duration.ofSeconds(120), SdnCatalogueRefresher.retryDelay(3));
        assertEquals(Duration.ofSeconds(480), SdnCatalogueRefresher.retryDelay(5));
        assertEquals(SdnCatalogueRefresher.MAX_RETRY, SdnCatalogueRefresher.retryDelay(6));
        assertEquals(SdnCatalogueRefresher.MAX_RETRY, SdnCatalogueRefresher.retryDelay(1_000));
    }

    @Test
    void consecutiveFailures_backOff_andASuccessResetsTheSchedule() {
        ScriptedFetch fetch = new ScriptedFetch(failed(), failed(), delivered("1.0"));
        SdnCatalogueRefresher refresher = direct(fetch);

        refresher.tick();
        assertEquals(T0.plus(Duration.ofSeconds(30)), refresher.nextDue());

        clock.set(refresher.nextDue());
        refresher.tick();
        assertEquals(clock.instant().plus(Duration.ofSeconds(60)), refresher.nextDue());

        clock.set(refresher.nextDue());
        refresher.tick();
        assertEquals(clock.instant().plus(SdnCatalogueRefresher.REFRESH_INTERVAL),
                refresher.nextDue());
    }

    // ------------------------------------------------------------------ in-flight guard

    @Test
    void noSecondFetchStarts_whileOneIsInFlight() {
        CountingFetch fetch = new CountingFetch(delivered("1.0"));
        QueuedExecutor executor = new QueuedExecutor();
        SdnCatalogueRefresher refresher =
                new SdnCatalogueRefresher(fetch, executor, clock, () -> NO_JITTER);

        refresher.tick();
        refresher.tick();
        refresher.requestNow();
        clock.set(T0.plus(Duration.ofHours(1)));
        refresher.tick();

        assertEquals(1, executor.pending(), "one fetch queued, however often it was asked");
        assertTrue(refresher.isFetching());

        executor.runAll();
        assertFalse(refresher.isFetching());
        refresher.requestNow();
        assertEquals(1, executor.pending(), "a new fetch may start once the last one finished");
    }

    @Test
    void aFetchThatThrows_releasesTheGuard_andIsReportedAsAFailure() {
        SdnCatalogueRefresher refresher = direct(() -> {
            throw new IllegalStateException("boom");
        });

        refresher.tick();

        assertFalse(refresher.isFetching());
        assertEquals(Optional.of(new SdnCatalogueResult.Failed("boom")), refresher.shown());
    }

    // ------------------------------------------------------------------ error does not clobber good

    @Test
    void aFailedRefresh_keepsTheGoodList_andRecordsTheError() {
        ScriptedFetch fetch = new ScriptedFetch(delivered("1.0"), failed());
        SdnCatalogueRefresher refresher = direct(fetch);
        refresher.tick();

        clock.set(T0.plus(SdnCatalogueRefresher.REFRESH_INTERVAL));
        refresher.requestNow();

        assertEquals(Optional.of(delivered("1.0")), refresher.shown());
        assertEquals(Optional.of(failed()), refresher.lastError());
    }

    @Test
    void everyKindOfBadResult_keepsTheGoodList() {
        List<SdnCatalogueResult> bad = List.of(failed(),
                new SdnCatalogueResult.CourierUnavailable(),
                new SdnCatalogueResult.NotSignedIn(),
                new SdnCatalogueResult.SubscriptionRequired(),
                new SdnCatalogueResult.Delivered(List.of(entry("0.9")), true));
        for (SdnCatalogueResult result : bad) {
            SdnCatalogueRefresher refresher = direct(new ScriptedFetch(delivered("1.0"), result));
            refresher.tick();
            refresher.requestNow();
            assertEquals(Optional.of(delivered("1.0")), refresher.shown(), result.toString());
        }
    }

    @Test
    void aFailureBeforeAnyGoodList_isShown() {
        SdnCatalogueRefresher refresher = direct(new ScriptedFetch(failed()));

        refresher.tick();

        assertEquals(Optional.of(failed()), refresher.shown());
        assertEquals(Optional.empty(), refresher.lastError());
    }

    @Test
    void aGoodListAfterAFailure_clearsTheError() {
        SdnCatalogueRefresher refresher =
                direct(new ScriptedFetch(delivered("1.0"), failed(), delivered("1.1")));
        refresher.tick();
        refresher.requestNow();
        refresher.requestNow();

        assertEquals("1.1", versionShown(refresher));
        assertEquals(Optional.empty(), refresher.lastError());
    }

    // ------------------------------------------------------------------ helpers

    private SdnCatalogueRefresher direct(Supplier<SdnCatalogueResult> fetch) {
        return new SdnCatalogueRefresher(fetch, Runnable::run, clock, () -> NO_JITTER);
    }

    private Instant nextDueAfterSuccess(double draw) {
        SdnCatalogueRefresher refresher = new SdnCatalogueRefresher(
                new CountingFetch(delivered("1.0")), Runnable::run, clock, () -> draw);
        refresher.tick();
        return refresher.nextDue();
    }

    private static String versionShown(SdnCatalogueRefresher refresher) {
        return switch (refresher.shown().orElseThrow()) {
            case SdnCatalogueResult.Delivered delivered -> delivered.entries().getFirst().version();
            case SdnCatalogueResult.CourierUnavailable ignored -> "";
            case SdnCatalogueResult.NotSignedIn ignored -> "";
            case SdnCatalogueResult.SubscriptionRequired ignored -> "";
            case SdnCatalogueResult.Failed ignored -> "";
        };
    }

    private static SdnCatalogueResult delivered(String version) {
        return new SdnCatalogueResult.Delivered(List.of(entry(version)), false);
    }

    private static SdnCatalogueEntry entry(String version) {
        return new SdnCatalogueEntry(SCRIPT_ID, "Woodcutter", "author", "subscriber", version,
                "2", "", "", "com.example.Woodcutter", false, true);
    }

    private static SdnCatalogueResult failed() {
        return new SdnCatalogueResult.Failed("launcher said no");
    }

    private static final class ManualClock implements InstantSource {
        private Instant now;

        ManualClock(Instant start) {
            now = start;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class CountingFetch implements Supplier<SdnCatalogueResult> {
        private final SdnCatalogueResult result;
        private final AtomicInteger calls = new AtomicInteger();

        CountingFetch(SdnCatalogueResult result) {
            this.result = result;
        }

        @Override
        public SdnCatalogueResult get() {
            calls.incrementAndGet();
            return result;
        }

        int calls() {
            return calls.get();
        }
    }

    /** Answers with each result in turn, repeating the last. */
    private static final class ScriptedFetch implements Supplier<SdnCatalogueResult> {
        private final Deque<SdnCatalogueResult> results;

        ScriptedFetch(SdnCatalogueResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public SdnCatalogueResult get() {
            return results.size() > 1 ? results.poll() : results.peek();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        int pending() {
            return queue.size();
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }
}
