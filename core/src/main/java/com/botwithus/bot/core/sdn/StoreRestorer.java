package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.launcher.LauncherProtocol;

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
 * script on the {@link StoreShelf} was delivered for. Each id is asked for on its
 * own, so one the launcher cannot deliver costs only itself. An id a fresh catalogue
 * no longer lists is set aside for the rest of this run, and so is one the launcher
 * refused {@value #REFUSALS_BEFORE_SET_ASIDE} times in a row once it had shown it
 * could deliver others. A stale catalogue sets nothing aside: it may simply predate
 * the purchase.
 *
 * <p>{@link #request} is the timer's trigger and is enough on its own. {@link #nudge}
 * is a client connecting: after a failure that a missing game client explains, it
 * may start one restore before the wait is over, once per such failure and never
 * within {@link #RETRY_AFTER} of the last attempt, so it shortens the longer waits
 * of repeated failures without letting connects pace the restore. At most one
 * restore is in flight however many callers ask, and after a failure the next waits
 * {@link #RETRY_AFTER}, doubling to {@link #MAX_RETRY_AFTER}.
 *
 * <p>Thread-safe. The restore itself runs on the executor given, because it blocks
 * on the launcher.
 */
public final class StoreRestorer {

    /** The wait after the first failed restore; it doubles with each failure in a row. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(60);

    /** The longest wait between failed restores. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(10);

    /**
     * How many restores in a row may refuse one id, while delivering others, before
     * it is set aside for the run. Three: one refusal can be a passing server error,
     * three with the launcher demonstrably working is that script.
     */
    public static final int REFUSALS_BEFORE_SET_ASIDE = 3;

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
     * @param stalledReason   why an id cannot be installed again this run because its
     *                        script stalled while loading; empty for any other id
     */
    public record Deps(InstalledScriptsLedger ledger, StoreShelf shelf,
                       Supplier<Optional<SdnCatalogueResult>> catalogue, BooleanSupplier deliveryEnabled,
                       Function<List<SdnCatalogueEntry>, SdnInstallResult> installer,
                       Consumer<List<BotScript>> onRestored, Executor executor, InstantSource clock,
                       Function<String, Optional<String>> stalledReason) {

        /** Deps for an installer that never reports a stalled script. */
        public Deps(InstalledScriptsLedger ledger, StoreShelf shelf,
                    Supplier<Optional<SdnCatalogueResult>> catalogue, BooleanSupplier deliveryEnabled,
                    Function<List<SdnCatalogueEntry>, SdnInstallResult> installer,
                    Consumer<List<BotScript>> onRestored, Executor executor, InstantSource clock) {
            this(ledger, shelf, catalogue, deliveryEnabled, installer, onRestored, executor, clock,
                    id -> Optional.empty());
        }
    }

    /**
     * How one restore went.
     *
     * @param isAnyRestored whether at least one script came back
     * @param failure       why the rest did not, when something did not
     * @param isLinkFailure whether a launcher with no game client to deliver through
     *                      would explain the failure, so a client connecting is worth a retry
     */
    private record Outcome(boolean isAnyRestored, Optional<String> failure, boolean isLinkFailure) {

        static Outcome failed(String failure, boolean isLinkFailure) {
            return new Outcome(false, Optional.of(failure), isLinkFailure);
        }
    }

    private final Deps deps;
    private final Object lock = new Object();
    // Everything below is guarded by lock.
    private final Map<String, String> setAside = new HashMap<>();
    private final Map<String, Integer> refusalsInARow = new HashMap<>();
    private final List<Runnable> settleWaiters = new ArrayList<>();
    private final List<Runnable> restoreWaiters = new ArrayList<>();
    private boolean isRunning;
    private boolean hasAttempted;
    private Instant failedAt;
    private int failuresInARow;
    private String lastFailure;
    private boolean isLinkFailure;
    private boolean isNudgeSpent;
    /** When the last restore started; a nudge never starts one sooner than {@link #RETRY_AFTER} after it. */
    private Instant lastAttemptAt;
    /** Whether any restore this run brought a script back, which shows the launcher can deliver. */
    private boolean hasRestoredAny;

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

    /** Ids that will not be restored this run, with why. Only ever grows. */
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
     * False after a failure, so a caller is told the reason rather than left waiting.
     */
    public boolean isAwaited() {
        synchronized (lock) {
            return isAwaitedLocked();
        }
    }

    /**
     * Runs {@code then} once the awaited restore settles, however it went, and returns
     * true; returns false without running it when none is awaited.
     */
    public boolean whenSettled(Runnable then) {
        synchronized (lock) {
            if (!isAwaitedLocked()) {
                return false;
            }
            settleWaiters.add(then);
            return true;
        }
    }

    /**
     * Runs {@code then} after the next restore that brings any script back, however
     * many fail before it. This is how a script reported missing after a failed
     * restore still starts when a later one delivers it.
     */
    public void whenRestored(Runnable then) {
        synchronized (lock) {
            restoreWaiters.add(then);
        }
    }

    /** Starts a restore if one is due; otherwise does nothing. Never blocks. The timer's trigger. */
    public void request() {
        request(false);
    }

    /**
     * A client connected. As {@link #request}, except that after a failure a missing
     * game client explains, it starts a restore before the wait is over, once per
     * such failure, since a connected client is what such a launcher was waiting for.
     */
    public void nudge() {
        request(true);
    }

    private void request(boolean isNudge) {
        if (pending().isEmpty()) {
            return;
        }
        if (!deps.deliveryEnabled().getAsBoolean()) {
            settleWithoutRunning("this host was not started by the launcher", false);
            return;
        }
        Optional<SdnCatalogueResult> shown = deps.catalogue().get();
        if (shown.isEmpty()) {
            return;
        }
        Optional<SdnCatalogueResult.Delivered> catalogue = delivered(shown.get());
        if (catalogue.isEmpty()) {
            settleWithoutRunning("the Store catalogue could not be read: " + describe(shown.get()),
                    isLauncherSilent(shown.get()));
            return;
        }
        if (claim(isNudge)) {
            deps.executor().execute(() -> run(catalogue.get()));
        }
    }

    // ── One restore ────────────────────────────────────────────────────────

    /** Takes the single in-flight slot, unless it is taken or the wait after a failure is not over. */
    private boolean claim(boolean isNudge) {
        synchronized (lock) {
            if (isRunning) {
                return false;
            }
            if (isWaitingAfterFailure()) {
                if (!isNudge || !isLinkFailure || isNudgeSpent || isWithinNudgeFloor()) {
                    return false;
                }
                isNudgeSpent = true;
                log.info("SDN: a client connected; retrying the Store restore before the wait is over");
            }
            isRunning = true;
            lastAttemptAt = deps.clock().instant();
            return true;
        }
    }

    private void run(SdnCatalogueResult.Delivered catalogue) {
        Outcome outcome;
        try {
            outcome = restore(catalogue);
        } catch (VirtualMachineError e) {
            settle(Outcome.failed("something went wrong: " + e, false));
            throw e;
        } catch (RuntimeException | Error e) {
            log.error("SDN: restoring Store scripts threw", e);
            outcome = Outcome.failed("something went wrong: " + e, false);
        }
        settle(outcome);
    }

    /** Asks for each pending id the catalogue lists, one at a time. */
    private Outcome restore(SdnCatalogueResult.Delivered catalogue) {
        Set<String> wanted = pending();
        List<SdnCatalogueEntry> listed = catalogue.entries().stream().filter(e -> wanted.contains(e.id()))
                .toList();
        if (!catalogue.stale()) {
            setAsideUnlisted(wanted, listed);
        }
        listed = withoutStalled(listed);
        if (listed.isEmpty()) {
            return new Outcome(false, Optional.empty(), false);
        }
        log.info("SDN: asking the launcher again for {} Store script(s) installed on this PC", listed.size());
        return restoreEach(listed);
    }

    /**
     * Restores each entry on its own. A launcher that is unreachable or not set up
     * for this host stops the run, since every other id would fail the same way.
     */
    private Outcome restoreEach(List<SdnCatalogueEntry> listed) {
        RunTally tally = new RunTally();
        for (SdnCatalogueEntry entry : listed) {
            Optional<Outcome> stop = restoreOne(entry, tally);
            if (stop.isPresent()) {
                return stop.get();
            }
        }
        countRefusals(tally.refusedIds, tally.isAnyRestored);
        return new Outcome(tally.isAnyRestored, Optional.ofNullable(tally.failure), tally.isLinkFailure);
    }

    /** One entry's restore. Returns the run's outcome when it must stop here. */
    private Optional<Outcome> restoreOne(SdnCatalogueEntry entry, RunTally tally) {
        return switch (deps.installer().apply(List.of(entry))) {
            case SdnInstallResult.Installed installed -> {
                Optional<SdnInstallResult.ScriptRefusal> refusal = installed.refusalFor(entry.id());
                if (refusal.isPresent()) {
                    yield refused(entry, refusal.get(), tally);
                }
                restored(entry, installed, tally);
                yield Optional.empty();
            }
            case SdnInstallResult.NothingSelected ignored -> Optional.empty();
            case SdnInstallResult.DeliveryDisabled ignored -> Optional.of(new Outcome(tally.isAnyRestored,
                    Optional.of("this host was not started by the launcher"), false));
            case SdnInstallResult.CourierUnavailable none -> Optional.of(new Outcome(tally.isAnyRestored,
                    Optional.of(entry.name() + ": " + none.text()), true));
            case SdnInstallResult.Refused refusedAll -> refused(entry, refusedAll.refusals().getFirst(), tally);
            case SdnInstallResult.Failed failed -> {
                tally.refused(entry, failed.reason());
                yield Optional.empty();
            }
        };
    }

    /**
     * The launcher said why it would not deliver {@code entry}.
     *
     * <ul>
     *   <li>A reason about the script that holds for every retry this run sets it
     *       aside with the user's text.
     *   <li>{@code not_owned} means this host is not the launcher's: nothing will be
     *       delivered until it is restarted from the launcher, so every pending id is
     *       set aside with that text, and no client connecting can help.
     *   <li>Any other reason about the host, its launcher or its account stops the run
     *       uncounted, since every other id would get the same answer.
     *   <li>Anything else, an untyped refusal or a server error, is about the script
     *       and counts toward {@link #REFUSALS_BEFORE_SET_ASIDE}.
     * </ul>
     *
     * @return the run's outcome when it stops here
     */
    private Optional<Outcome> refused(SdnCatalogueEntry entry, SdnInstallResult.ScriptRefusal refusal,
                                      RunTally tally) {
        Optional<String> reason = refusal.reason();
        if (reason.filter(SdnReasonText::isLastingForThisRun).isPresent()) {
            setAsideAll(List.of(entry.id()), refusal.text());
            return Optional.empty();
        }
        if (reason.filter(SdnReasonText.NOT_OWNED::equals).isPresent()) {
            List<String> all = new ArrayList<>(pending());
            setAsideAll(all, refusal.text());
            return Optional.of(new Outcome(tally.isAnyRestored, Optional.of(refusal.text()), false));
        }
        if (reason.filter(SdnReasonText::isHostReason).isPresent()) {
            return Optional.of(new Outcome(tally.isAnyRestored, Optional.of(refusal.text()),
                    reason.filter(SdnReasonText::isLinkReason).isPresent()));
        }
        tally.refusedWith(entry, refusal.text(), reason.isEmpty());
        return Optional.empty();
    }

    private void setAsideAll(List<String> ids, String text) {
        log.info("SDN: not restoring {} Store script(s) this run: {}", ids.size(), text);
        synchronized (lock) {
            ids.forEach(id -> setAside.put(id, text));
        }
    }

    private void restored(SdnCatalogueEntry entry, SdnInstallResult.Installed installed, RunTally tally) {
        if (installed.scriptsFor(entry.id()).isEmpty()) {
            log.warn("SDN: not restoring {} this run: {}", entry.name(), NOT_DELIVERED);
            synchronized (lock) {
                setAside.put(entry.id(), NOT_DELIVERED);
            }
            return;
        }
        tally.isAnyRestored = true;
        synchronized (lock) {
            refusalsInARow.remove(entry.id());
        }
        deps.onRestored().accept(installed.scripts());
    }

    /**
     * Counts a refusal against each refused id once the launcher has shown, in this
     * run or an earlier one since the host started, that it can deliver: then the id
     * is the problem, not the link. Without that, nothing is counted, since a
     * launcher with no game client refuses every id alike.
     */
    private void countRefusals(List<String> refusedIds, boolean isAnyRestoredNow) {
        synchronized (lock) {
            hasRestoredAny |= isAnyRestoredNow;
            if (!hasRestoredAny) {
                return;
            }
            for (String id : refusedIds) {
                int refusals = refusalsInARow.merge(id, 1, Integer::sum);
                if (refusals >= REFUSALS_BEFORE_SET_ASIDE) {
                    setAside.put(id, "the launcher refused it " + refusals + " times in a row");
                    log.warn("SDN: not restoring Store script {} this run: refused {} times in a row", id, refusals);
                }
            }
        }
    }

    /**
     * Sets aside each entry whose script stalled while loading earlier in this run,
     * with its own message: asking again would only fail fast, and it must not stand
     * in the way of the rest.
     */
    private List<SdnCatalogueEntry> withoutStalled(List<SdnCatalogueEntry> listed) {
        List<SdnCatalogueEntry> rest = new ArrayList<>();
        for (SdnCatalogueEntry entry : listed) {
            Optional<String> stalled = deps.stalledReason().apply(entry.id());
            if (stalled.isEmpty()) {
                rest.add(entry);
                continue;
            }
            log.info("SDN: not restoring {} this run: {}", entry.name(), stalled.get());
            synchronized (lock) {
                setAside.put(entry.id(), stalled.get());
            }
        }
        return rest;
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

    /** What one run has seen so far. Confined to the restore thread. */
    private static final class RunTally {
        private final List<String> refusedIds = new ArrayList<>();
        private boolean isAnyRestored;
        private boolean isLinkFailure;
        private String failure;

        void refused(SdnCatalogueEntry entry, String reason) {
            refusedWith(entry, entry.name() + ": " + reason, LauncherProtocol.ERROR_SDN_NOT_DELIVERED.equals(reason));
        }

        /** A refusal whose {@code text} already names the script. */
        void refusedWith(SdnCatalogueEntry entry, String text, boolean isLink) {
            refusedIds.add(entry.id());
            isLinkFailure |= isLink;
            if (failure == null) {
                failure = text;
            }
        }
    }

    // ── Settling ───────────────────────────────────────────────────────────

    /**
     * Records a failure that needed no restore to find, at most once per wait, atomically.
     *
     * @param isLinkFailure whether a client connecting could help, as it can when the
     *                      launcher did not answer at all, which is most likely just
     *                      after it updated itself
     */
    private void settleWithoutRunning(String reason, boolean isLinkFailure) {
        List<Runnable> toRun;
        synchronized (lock) {
            if (isRunning || isWaitingAfterFailure()) {
                return;
            }
            toRun = settleLocked(Outcome.failed(reason, isLinkFailure));
        }
        finish(Outcome.failed(reason, isLinkFailure), toRun);
    }

    private static boolean isLauncherSilent(SdnCatalogueResult shown) {
        return switch (shown) {
            case SdnCatalogueResult.CourierUnavailable ignored -> true;
            case SdnCatalogueResult.Delivered ignored -> false;
            case SdnCatalogueResult.NotSignedIn ignored -> false;
            case SdnCatalogueResult.SubscriptionRequired ignored -> false;
            case SdnCatalogueResult.Failed ignored -> false;
        };
    }

    private void settle(Outcome outcome) {
        List<Runnable> toRun;
        synchronized (lock) {
            toRun = settleLocked(outcome);
        }
        finish(outcome, toRun);
    }

    /** The state change of a settle; returns the waiters to run once the lock is released. */
    private List<Runnable> settleLocked(Outcome outcome) {
        isRunning = false;
        hasAttempted = true;
        lastFailure = outcome.failure().orElse(null);
        boolean isFailure = outcome.failure().isPresent();
        failedAt = isFailure ? deps.clock().instant() : null;
        failuresInARow = isFailure ? failuresInARow + 1 : 0;
        isLinkFailure = outcome.isLinkFailure();
        isNudgeSpent = false;
        List<Runnable> toRun = new ArrayList<>(settleWaiters);
        settleWaiters.clear();
        if (outcome.isAnyRestored()) {
            toRun.addAll(restoreWaiters);
            restoreWaiters.clear();
        }
        return toRun;
    }

    private static void finish(Outcome outcome, List<Runnable> toRun) {
        outcome.failure().ifPresent(reason -> log.warn("SDN: could not restore installed Store scripts: {}", reason));
        toRun.forEach(StoreRestorer::runQuietly);
    }

    /** Called with {@link #lock} held; {@link #pending} takes it again, which is re-entrant. */
    private boolean isAwaitedLocked() {
        return isRunning || (!hasAttempted && !pending().isEmpty());
    }

    /**
     * Whether a nudge would start a restore less than {@link #RETRY_AFTER} after the
     * last one started. This floor is what keeps a client that reconnects in a loop
     * from driving a restore per connect: a failed nudged restore earns a new nudge,
     * but not before the floor has passed.
     */
    private boolean isWithinNudgeFloor() {
        return lastAttemptAt != null && deps.clock().instant().isBefore(lastAttemptAt.plus(RETRY_AFTER));
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

    private static Optional<SdnCatalogueResult.Delivered> delivered(SdnCatalogueResult shown) {
        return switch (shown) {
            case SdnCatalogueResult.Delivered d -> Optional.of(d);
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
