package com.botwithus.bot.core.sdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Keeps the script catalogue current while the host runs, so a script published or updated
 * on the account shows up without pressing Refresh or restarting.
 *
 * <p>The refresher does not own a timer. Callers drive it: {@link #tick()} starts a fetch when
 * one is due, and {@link #requestNow()} starts one at once (the Refresh button). The UI calls
 * {@code tick()} every frame while the panel is on screen, which covers "became visible again
 * after the interval passed", and a background ticker calls it while the panel is hidden.</p>
 *
 * <p>The rules it guarantees, each pinned by {@code SdnCatalogueRefresherTest}:</p>
 * <ul>
 *   <li><b>Schedule.</b> After a good catalogue, the next fetch is due in
 *       {@link #REFRESH_INTERVAL} with &plusmn;{@link #JITTER_FRACTION} jitter, so hosts started
 *       together do not all ask the launcher at the same moment.</li>
 *   <li><b>Backoff.</b> After a failed fetch, the retry is due in {@link #FIRST_RETRY}, doubling
 *       with each consecutive failure up to {@link #MAX_RETRY}, jittered the same way. A launcher
 *       that stays down is asked less and less often. One good catalogue resets it.</li>
 *   <li><b>One at a time.</b> No fetch starts while another is in flight, whoever asks.</li>
 *   <li><b>A good list survives a bad refresh.</b> Once a fresh catalogue has been shown, a
 *       failure, or a stale copy of an older answer, never replaces it. The failure is kept
 *       beside it as {@link #lastError()} so the UI can say the list may be out of date.</li>
 * </ul>
 *
 * <p>Fetches run on the supplied executor, never on the caller's thread: a fetch waits up to
 * several seconds for the launcher.</p>
 */
public final class SdnCatalogueRefresher {

    /** How long a good catalogue is trusted before it is fetched again. */
    public static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    /** Delay before the first retry after a failure. */
    public static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    /** Longest delay between retries while failures continue. */
    public static final Duration MAX_RETRY = Duration.ofMinutes(10);
    /** Each delay is scaled by a factor drawn from {@code [1 - this, 1 + this)}. */
    public static final double JITTER_FRACTION = 0.10;

    private static final Logger log = LoggerFactory.getLogger(SdnCatalogueRefresher.class);
    private static final int BACKOFF_BASE = 2;
    private static final int MAX_DOUBLINGS = 30;

    private final Supplier<SdnCatalogueResult> fetch;
    private final Executor fetchExecutor;
    private final InstantSource clock;
    private final DoubleSupplier unitRandom;

    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final Object lock = new Object();
    private SdnCatalogueResult shown;
    private SdnCatalogueResult lastError;
    private Instant nextDue;
    private int consecutiveFailures;

    /**
     * @param fetch         asks the launcher for the catalogue; may block
     * @param fetchExecutor where each fetch runs
     * @param clock         the time source; tests pass a controllable one
     * @param unitRandom    returns values in {@code [0, 1)}; drives the jitter
     */
    public SdnCatalogueRefresher(Supplier<SdnCatalogueResult> fetch, Executor fetchExecutor,
                                 InstantSource clock, DoubleSupplier unitRandom) {
        this.fetch = fetch;
        this.fetchExecutor = fetchExecutor;
        this.clock = clock;
        this.unitRandom = unitRandom;
        this.nextDue = clock.instant();
    }

    /** Starts a fetch if one is due and none is in flight. Cheap; call it every frame. */
    public void tick() {
        Instant due;
        synchronized (lock) {
            due = nextDue;
        }
        if (!clock.instant().isBefore(due)) {
            start();
        }
    }

    /** Starts a fetch now unless one is already in flight (the Refresh button). */
    public void requestNow() {
        start();
    }

    /** What the panel should show, or empty before the first fetch has finished. */
    public Optional<SdnCatalogueResult> shown() {
        synchronized (lock) {
            return Optional.ofNullable(shown);
        }
    }

    /**
     * The most recent failed refresh while an older, good catalogue is still shown; empty when
     * the last refresh succeeded or nothing good has been shown yet.
     */
    public Optional<SdnCatalogueResult> lastError() {
        synchronized (lock) {
            return Optional.ofNullable(lastError);
        }
    }

    public boolean isFetching() {
        return inFlight.get();
    }

    /** When the next automatic fetch is due. */
    public Instant nextDue() {
        synchronized (lock) {
            return nextDue;
        }
    }

    private void start() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            fetchExecutor.execute(this::runFetch);
        } catch (RuntimeException e) {
            inFlight.set(false);
            throw e;
        }
    }

    private void runFetch() {
        try {
            apply(fetchSafely());
        } finally {
            inFlight.set(false);
        }
    }

    private SdnCatalogueResult fetchSafely() {
        try {
            return fetch.get();
        } catch (RuntimeException e) {
            log.warn("SDN: catalogue refresh threw", e);
            return new SdnCatalogueResult.Failed(String.valueOf(e.getMessage()));
        }
    }

    private void apply(SdnCatalogueResult result) {
        synchronized (lock) {
            if (isFresh(result)) {
                shown = result;
                lastError = null;
                consecutiveFailures = 0;
                nextDue = clock.instant().plus(jittered(REFRESH_INTERVAL));
                return;
            }
            consecutiveFailures++;
            nextDue = clock.instant().plus(jittered(retryDelay(consecutiveFailures)));
            if (shown == null || !isFresh(shown)) {
                shown = result;
                return;
            }
            lastError = result;
        }
    }

    /** A catalogue the launcher answered this time: the only result allowed to replace one. */
    private static boolean isFresh(SdnCatalogueResult result) {
        return switch (result) {
            case SdnCatalogueResult.Delivered delivered -> !delivered.stale();
            case SdnCatalogueResult.CourierUnavailable ignored -> false;
            case SdnCatalogueResult.NotSignedIn ignored -> false;
            case SdnCatalogueResult.SubscriptionRequired ignored -> false;
            case SdnCatalogueResult.Failed ignored -> false;
        };
    }

    /** {@code FIRST_RETRY * 2^(failures - 1)}, capped at {@link #MAX_RETRY}. */
    static Duration retryDelay(int failures) {
        int doublings = Math.min(failures - 1, MAX_DOUBLINGS);
        Duration delay = FIRST_RETRY.multipliedBy((long) Math.pow(BACKOFF_BASE, doublings));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    private Duration jittered(Duration base) {
        double factor = 1.0 - JITTER_FRACTION + 2.0 * JITTER_FRACTION * unitRandom.getAsDouble();
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
