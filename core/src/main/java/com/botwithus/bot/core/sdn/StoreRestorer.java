package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Asks the launcher again for the Store scripts this PC installed, so an install
 * survives a host restart.
 *
 * <p>What to ask for is the {@link InstalledScriptsLedger}'s catalogue ids that no
 * script on the {@link StoreShelf} was delivered for. Only ids still in the
 * catalogue are asked for; an id the catalogue no longer lists (the subscription
 * lapsed, or the script was withdrawn) is set aside for the rest of this run with
 * its reason, so it is never asked for again and nothing waits on it.
 *
 * <p>{@link #request} is the one trigger and is cheap to call often: the host calls
 * it on a timer from start-up, which alone is enough, and again whenever a client
 * connects, which only makes a retry come sooner. At most one restore is in flight,
 * however many callers ask at once, and after a failed one the next waits
 * {@link #RETRY_AFTER}, so neither many clients connecting together nor a launcher
 * that keeps refusing is asked in a burst.
 *
 * <p>Thread-safe. The restore itself runs on the executor given, because it
 * blocks on the launcher.
 */
public final class StoreRestorer {

    /** The wait after the first failed restore; it doubles with each failure in a row. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(60);

    /** The longest wait between failed restores. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(10);

    /** Why an id is set aside when the catalogue does not list it. */
    static final String NOT_IN_CATALOGUE = "it is no longer in your Store catalogue";

    /** Why an id is set aside when a delivery that did arrive had nothing for it. */
    static final String NOT_DELIVERED = "the launcher's delivery did not include it";

    private static final Logger log = LoggerFactory.getLogger(StoreRestorer.class);

    /**
     * What the restorer needs from the host.
     *
     * @param ledger          the persisted install record; ids only
     * @param shelf           what has been delivered since the host started
     * @param catalogue       the catalogue shown now; empty while the first fetch is out
     * @param deliveryEnabled whether the launcher can hand this host a script at all
     * @param installer       asks the launcher for entries and loads them; blocks
     * @param onRestored      handed the scripts each successful restore delivered
     * @param executor        runs each restore
     * @param clock           times the wait after a failure
     */
    public record Deps(InstalledScriptsLedger ledger, StoreShelf shelf,
                       Supplier<Optional<SdnCatalogueResult>> catalogue, BooleanSupplier deliveryEnabled,
                       Function<List<SdnCatalogueEntry>, SdnInstallResult> installer,
                       Consumer<List<BotScript>> onRestored, Executor executor, InstantSource clock) {}

    private final Deps deps;
    private final Object lock = new Object();
    // Everything below is guarded by lock.
    private final Map<String, String> setAside = new HashMap<>();
    private final List<Runnable> waiters = new ArrayList<>();
    private boolean isRunning;
    private boolean hasAttempted;
    private Instant failedAt;
    private int failuresInARow;
    private String lastFailure;

    public StoreRestorer(Deps deps) {
        this.deps = deps;
    }

    /** The catalogue ids still to be restored: installed here, not delivered yet, not set aside. */
    public Set<String> pending() {
        Set<String> ids = deps.ledger().all().values().stream()
                .map(InstalledSdnScript::catalogueId)
                .collect(Collectors.toCollection(HashSet::new));
        ids.removeAll(deps.shelf().catalogueIds());
        synchronized (lock) {
            ids.removeAll(setAside.keySet());
        }
        return Set.copyOf(ids);
    }

    /**
     * Ids that will not be restored this run, with why. Only ever grows.
     */
    public Map<String, String> setAside() {
        synchronized (lock) {
            return Map.copyOf(setAside);
        }
    }

    /** Why the last restore failed; empty when it did not, or none has run. */
    public Optional<String> lastFailure() {
        synchronized (lock) {
            return Optional.ofNullable(lastFailure);
        }
    }

    /**
     * Whether a restore is still to come that something could usefully wait for:
     * one is running, or none has been tried yet and there is something to restore.
     * False after a failure, so a caller is told the reason rather than left waiting
     * for a retry that may never succeed.
     */
    public boolean isAwaited() {
        synchronized (lock) {
            return isAwaitedLocked();
        }
    }

    /**
     * Runs {@code then} once the restore that is awaited settles, and returns true;
     * returns false without running it when none is awaited. Atomic with
     * {@link #isAwaited}, so a restore settling meanwhile cannot strand the waiter.
     */
    public boolean whenSettled(Runnable then) {
        synchronized (lock) {
            if (!isAwaitedLocked()) {
                return false;
            }
            waiters.add(then);
            return true;
        }
    }

    /** Starts a restore if one is due; otherwise does nothing. Never blocks. */
    public void request() {
        if (pending().isEmpty()) {
            return;
        }
        if (!deps.deliveryEnabled().getAsBoolean()) {
            settleWithoutRunning("this host was not started by the launcher");
            return;
        }
        Optional<SdnCatalogueResult> shown = deps.catalogue().get();
        if (shown.isEmpty()) {
            return;
        }
        Optional<List<SdnCatalogueEntry>> entries = deliveredEntries(shown.get());
        if (entries.isEmpty()) {
            settleWithoutRunning("the Store catalogue could not be read: " + describe(shown.get()));
            return;
        }
        if (claim()) {
            deps.executor().execute(() -> run(entries.get()));
        }
    }

    // ── One restore ────────────────────────────────────────────────────────

    /** Takes the single in-flight slot, unless it is taken or a failure is too recent. */
    private boolean claim() {
        synchronized (lock) {
            if (isRunning || isWaitingAfterFailure()) {
                return false;
            }
            isRunning = true;
            return true;
        }
    }

    private void run(List<SdnCatalogueEntry> catalogue) {
        Optional<String> failure;
        try {
            failure = restore(catalogue);
        } catch (RuntimeException e) {
            log.warn("SDN: restoring Store scripts threw", e);
            failure = Optional.of("something went wrong: " + e.getMessage());
        }
        settle(failure);
    }

    /** Asks for every pending id the catalogue lists. Returns why it failed, or empty. */
    private Optional<String> restore(List<SdnCatalogueEntry> catalogue) {
        Set<String> wanted = pending();
        List<SdnCatalogueEntry> listed = catalogue.stream().filter(e -> wanted.contains(e.id())).toList();
        setAsideUnlisted(wanted, listed);
        if (listed.isEmpty()) {
            return Optional.empty();
        }
        log.info("SDN: asking the launcher again for {} Store script(s) installed on this PC", listed.size());
        return switch (deps.installer().apply(listed)) {
            case SdnInstallResult.Installed installed -> {
                setAsideUndelivered(listed, installed);
                deps.onRestored().accept(installed.scripts());
                yield Optional.empty();
            }
            case SdnInstallResult.NothingSelected ignored -> Optional.empty();
            case SdnInstallResult.DeliveryDisabled ignored ->
                    Optional.of("this host was not started by the launcher");
            case SdnInstallResult.CourierUnavailable ignored ->
                    Optional.of("the launcher did not deliver them");
            case SdnInstallResult.Failed failed -> Optional.of(failed.reason());
        };
    }

    /**
     * Sets aside each id the delivery had no script for. Asking again would fetch
     * the same delivery, so without this an unpairable one would be fetched forever.
     */
    private void setAsideUndelivered(List<SdnCatalogueEntry> listed, SdnInstallResult.Installed installed) {
        List<String> missing = listed.stream().map(SdnCatalogueEntry::id)
                .filter(id -> installed.scriptsFor(id).isEmpty()).toList();
        if (missing.isEmpty()) {
            return;
        }
        log.warn("SDN: not restoring {} installed Store script(s): {}", missing.size(), NOT_DELIVERED);
        synchronized (lock) {
            missing.forEach(id -> setAside.put(id, NOT_DELIVERED));
        }
    }

    private void setAsideUnlisted(Set<String> wanted, List<SdnCatalogueEntry> listed) {
        Set<String> unlisted = new HashSet<>(wanted);
        listed.forEach(e -> unlisted.remove(e.id()));
        if (unlisted.isEmpty()) {
            return;
        }
        log.info("SDN: not restoring {} installed Store script(s): {}", unlisted.size(), NOT_IN_CATALOGUE);
        synchronized (lock) {
            unlisted.forEach(id -> setAside.put(id, NOT_IN_CATALOGUE));
        }
    }

    // ── Settling ───────────────────────────────────────────────────────────

    /** Records a failure that needed no restore to find, at most once per wait. */
    private void settleWithoutRunning(String reason) {
        synchronized (lock) {
            if (isRunning || isWaitingAfterFailure()) {
                return;
            }
        }
        settle(Optional.of(reason));
    }

    private void settle(Optional<String> failure) {
        List<Runnable> toRun;
        synchronized (lock) {
            isRunning = false;
            hasAttempted = true;
            lastFailure = failure.orElse(null);
            failedAt = failure.isPresent() ? deps.clock().instant() : null;
            failuresInARow = failure.isPresent() ? failuresInARow + 1 : 0;
            toRun = List.copyOf(waiters);
            waiters.clear();
        }
        failure.ifPresent(reason -> log.warn("SDN: could not restore installed Store scripts: {}", reason));
        toRun.forEach(StoreRestorer::runQuietly);
    }

    /** Called with {@link #lock} held; {@link #pending} takes it again, which is re-entrant. */
    private boolean isAwaitedLocked() {
        return isRunning || (!hasAttempted && !pending().isEmpty());
    }

    private boolean isWaitingAfterFailure() {
        return failedAt != null && deps.clock().instant().isBefore(failedAt.plus(retryWait()));
    }

    /** {@link #RETRY_AFTER}, doubled for each further failure in a row, up to {@link #MAX_RETRY_AFTER}. */
    private Duration retryWait() {
        Duration wait = RETRY_AFTER;
        for (int i = 1; i < failuresInARow && wait.compareTo(MAX_RETRY_AFTER) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(MAX_RETRY_AFTER) < 0 ? wait : MAX_RETRY_AFTER;
    }

    private static Optional<List<SdnCatalogueEntry>> deliveredEntries(SdnCatalogueResult shown) {
        return switch (shown) {
            case SdnCatalogueResult.Delivered d -> Optional.of(d.entries());
            case SdnCatalogueResult.CourierUnavailable ignored -> Optional.empty();
            case SdnCatalogueResult.NotSignedIn ignored -> Optional.empty();
            case SdnCatalogueResult.SubscriptionRequired ignored -> Optional.empty();
            case SdnCatalogueResult.Failed ignored -> Optional.empty();
        };
    }

    private static String describe(SdnCatalogueResult shown) {
        return switch (shown) {
            case SdnCatalogueResult.Delivered ignored -> "it was delivered";
            case SdnCatalogueResult.CourierUnavailable ignored -> "the launcher did not answer";
            case SdnCatalogueResult.NotSignedIn ignored -> "no account is signed in";
            case SdnCatalogueResult.SubscriptionRequired ignored -> "a subscription is required";
            case SdnCatalogueResult.Failed failed -> failed.reason();
        };
    }

    private static void runQuietly(Runnable waiter) {
        try {
            waiter.run();
        } catch (RuntimeException e) {
            log.warn("SDN: a caller waiting on the Store restore threw", e);
        }
    }
}
