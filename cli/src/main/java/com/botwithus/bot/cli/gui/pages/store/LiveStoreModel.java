package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.pages.StoreSignInLine;
import com.botwithus.bot.cli.sdn.FavouritesStore;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.SdnUpdateStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Script Store over the live host: the launcher's catalogue from the shared
 * {@link SdnCatalogueRefresher}, the scripts loaded on this PC, the Store's
 * install ledger and the user's favourites.
 *
 * <p>A catalogue script counts as on this PC when a loaded script has exactly the
 * class the catalogue names: a local build from {@code scripts/}, or a Store copy
 * registered on a client. Only a Store copy can be out of date, and only when the
 * ledger's build is behind the catalogue's; a local build is the user's own and is
 * never badged. A script with no class in the catalogue matches nothing loaded,
 * because a name is not an identity.</p>
 *
 * <p>Installs run on the executor given, never on the render thread, because each
 * waits for the launcher. A delivery is registered on every live client, which is
 * what gives it the same controls as a local script. Store scripts live in memory
 * only: a client that connects later, or a restart, does not get them back.</p>
 *
 * <p>{@link #view}, {@link #markSeen} and {@link #signInLine} are for the render
 * thread; installs settle from the install executor.</p>
 */
public final class LiveStoreModel implements StoreModel {

    /** What to say when the launcher cannot hand this host a script at all. */
    static final String DELIVERY_DISABLED = "This host was not started by the launcher, so it cannot install "
            + "scripts. Start it from the BotWithUs launcher.";

    private static final Logger log = LoggerFactory.getLogger(LiveStoreModel.class);

    private final SdnCatalogueRefresher refresher;
    private final Function<List<SdnCatalogueEntry>, SdnInstallResult> installer;
    private final Function<String, Optional<InstalledSdnScript>> ledger;
    private final BooleanSupplier deliveryEnabled;
    private final FavouritesStore favourites;
    private final Supplier<? extends Collection<Connection>> connections;
    private final Supplier<List<BotScript>> localScripts;
    private final Executor installExecutor;
    private final InstantSource clock;

    /** Catalogue ids with an install in flight. */
    private final Set<String> installing = ConcurrentHashMap.newKeySet();
    private volatile InstallActivity lastInstall = new InstallActivity.Idle();

    // Render thread only.
    private SdnCatalogueResult lastShown;
    private Instant syncedAt;
    private SdnCatalogueResult lastMarkedSeen;

    /**
     * @param refresher       the host's one catalogue loop, shared with Normal mode's picker
     * @param installer       asks the launcher for catalogue scripts and loads them; blocks
     * @param ledger          what the host recorded when it installed a script class from the Store
     * @param deliveryEnabled whether the launcher can hand this host a script at all
     * @param connections     the host's clients, read fresh on every call
     * @param localScripts    the scripts last loaded from the scripts folder
     * @param installExecutor runs each install; must not be the render thread
     * @param clock           stamps when a catalogue was first shown
     */
    public LiveStoreModel(SdnCatalogueRefresher refresher,
                          Function<List<SdnCatalogueEntry>, SdnInstallResult> installer,
                          Function<String, Optional<InstalledSdnScript>> ledger,
                          BooleanSupplier deliveryEnabled,
                          FavouritesStore favourites,
                          Supplier<? extends Collection<Connection>> connections,
                          Supplier<List<BotScript>> localScripts,
                          Executor installExecutor,
                          InstantSource clock) {
        this.refresher = refresher;
        this.installer = installer;
        this.ledger = ledger;
        this.deliveryEnabled = deliveryEnabled;
        this.favourites = favourites;
        this.connections = connections;
        this.localScripts = localScripts;
        this.installExecutor = installExecutor;
        this.clock = clock;
    }

    @Override
    public StoreView view(StoreQuery query) {
        refresher.tick();
        SdnCatalogueResult shown = refresher.shown().orElse(null);
        noteShown(shown);
        List<String> favouriteOrder = List.copyOf(favourites.favourites());
        List<StoreRow> rows = rows(entriesOf(shown), Set.copyOf(favouriteOrder));
        return StoreView.of(statusOf(shown), query, rows, favouriteOrder, refresher.isFetching(),
                deliveryEnabled.getAsBoolean(), activity());
    }

    @Override
    public void refresh() {
        refresher.requestNow();
    }

    @Override
    public void toggleFavourite(String id) {
        favourites.toggleFavourite(id);
    }

    @Override
    public void install(List<String> ids) {
        List<SdnCatalogueEntry> batch = installable(ids);
        if (batch.isEmpty()) {
            return;
        }
        if (!deliveryEnabled.getAsBoolean()) {
            lastInstall = new InstallActivity.Failed(DELIVERY_DISABLED);
            return;
        }
        batch.forEach(e -> installing.add(e.id()));
        installExecutor.execute(() -> runInstall(batch));
    }

    @Override
    public void dismissInstallMessage() {
        lastInstall = new InstallActivity.Idle();
    }

    @Override
    public void markSeen() {
        delivered(refresher.shown().orElse(null)).filter(d -> d != lastMarkedSeen).ifPresent(d -> {
            favourites.markSeen(d.entries().stream().map(SdnCatalogueEntry::id).toList());
            lastMarkedSeen = d;
        });
    }

    @Override
    public SecondLine.AccountStatus signInLine() {
        return StoreSignInLine.of(refresher.shown(), ids -> favourites.unseen(ids).size());
    }

    // ── The catalogue ──────────────────────────────────────────────────────

    /** Remembers when the catalogue now shown was first shown here. */
    private void noteShown(SdnCatalogueResult shown) {
        if (shown != lastShown) {
            lastShown = shown;
            syncedAt = clock.instant();
        }
    }

    private StoreStatus statusOf(SdnCatalogueResult shown) {
        return switch (shown) {
            case null -> new StoreStatus.Loading();
            case SdnCatalogueResult.Delivered d ->
                    new StoreStatus.Ready(d.stale(), syncedAt, refresher.lastError().map(LiveStoreModel::describe));
            case SdnCatalogueResult.CourierUnavailable ignored -> unavailable(StoreStatus.Reason.LAUNCHER_NOT_RUNNING, "");
            case SdnCatalogueResult.NotSignedIn ignored -> unavailable(StoreStatus.Reason.SIGNED_OUT, "");
            case SdnCatalogueResult.SubscriptionRequired ignored -> unavailable(StoreStatus.Reason.NO_SUBSCRIPTION, "");
            case SdnCatalogueResult.Failed failed -> unavailable(StoreStatus.Reason.FAILED, failed.reason());
        };
    }

    private static StoreStatus unavailable(StoreStatus.Reason reason, String detail) {
        return new StoreStatus.Unavailable(new StoreStatus.Notice(reason, detail));
    }

    /** Why a refresh failed, as the line over a list that is still shown says it. */
    private static String describe(SdnCatalogueResult error) {
        return switch (error) {
            case SdnCatalogueResult.Delivered ignored -> "the launcher did not answer";
            case SdnCatalogueResult.CourierUnavailable ignored -> "the launcher did not answer";
            case SdnCatalogueResult.NotSignedIn ignored -> "no account is signed in";
            case SdnCatalogueResult.SubscriptionRequired ignored -> "a subscription is required";
            case SdnCatalogueResult.Failed failed -> failed.reason();
        };
    }

    private static List<SdnCatalogueEntry> entriesOf(SdnCatalogueResult shown) {
        return delivered(shown).map(SdnCatalogueResult.Delivered::entries).orElse(List.of());
    }

    /** The catalogue in {@code shown}, fresh or stale; empty for every other answer and for none. */
    private static Optional<SdnCatalogueResult.Delivered> delivered(SdnCatalogueResult shown) {
        return switch (shown) {
            case null -> Optional.empty();
            case SdnCatalogueResult.Delivered d -> Optional.of(d);
            case SdnCatalogueResult.CourierUnavailable ignored -> Optional.empty();
            case SdnCatalogueResult.NotSignedIn ignored -> Optional.empty();
            case SdnCatalogueResult.SubscriptionRequired ignored -> Optional.empty();
            case SdnCatalogueResult.Failed ignored -> Optional.empty();
        };
    }

    // ── Rows ───────────────────────────────────────────────────────────────

    /** The class names loaded on this PC: local builds, and anything registered on a live client. */
    private record Loaded(Set<String> local, Set<String> onClients) {}

    private List<StoreRow> rows(List<SdnCatalogueEntry> entries, Set<String> favouriteIds) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Loaded loaded = loaded();
        return entries.stream()
                .map(e -> rowOf(e, stateOf(e, loaded), installing.contains(e.id()), favouriteIds.contains(e.id())))
                .toList();
    }

    private Loaded loaded() {
        Set<String> local = localScripts.get().stream().map(s -> s.getClass().getName()).collect(Collectors.toSet());
        Set<String> onClients = new HashSet<>();
        for (Connection conn : liveConnections()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                onClients.add(runner.getScript().getClass().getName());
            }
        }
        return new Loaded(local, onClients);
    }

    private RowState stateOf(SdnCatalogueEntry e, Loaded loaded) {
        String cls = e.scriptClass();
        if (cls == null || cls.isBlank()) {
            return new RowState.NotInstalled(false);
        }
        if (loaded.local().contains(cls)) {
            return new RowState.Installed();
        }
        Optional<InstalledSdnScript> record = ledger.apply(cls);
        if (!loaded.onClients().contains(cls)) {
            return new RowState.NotInstalled(record.isPresent());
        }
        SdnUpdateStatus update = record.map(r -> r.updateAgainst(e)).orElseGet(SdnUpdateStatus.Unknown::new);
        return switch (update) {
            case SdnUpdateStatus.Available available ->
                    new RowState.UpdateAvailable(available.installedBuild(), available.currentBuild());
            case SdnUpdateStatus.UpToDate ignored -> new RowState.Installed();
            case SdnUpdateStatus.Unknown ignored -> new RowState.Installed();
        };
    }

    private static StoreRow rowOf(SdnCatalogueEntry e, RowState state, boolean isInstalling, boolean isFavourite) {
        return new StoreRow(e.id(), e.name(), text(e.author()), e.summary(), text(e.description()),
                e.scriptCategory(), e.pricing(), StorePrices.describe(e.price()), text(e.version()),
                text(e.scriptClass()), e.agentv2Support(), e.agentv1Support(), e.isOwned(), state, isInstalling,
                isFavourite);
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    // ── Installs ───────────────────────────────────────────────────────────

    private InstallActivity activity() {
        int inFlight = installing.size();
        return inFlight > 0 ? new InstallActivity.Installing(inFlight) : lastInstall;
    }

    /** The catalogue entries {@code ids} name that can be installed now, in the order given. */
    private List<SdnCatalogueEntry> installable(List<String> ids) {
        List<SdnCatalogueEntry> entries = entriesOf(refresher.shown().orElse(null));
        Map<String, SdnCatalogueEntry> byId = entries.stream()
                .collect(Collectors.toMap(SdnCatalogueEntry::id, e -> e, (first, second) -> first));
        Set<String> installableIds = rows(entries, Set.of()).stream().filter(StoreRow::isInstallable)
                .map(StoreRow::id).collect(Collectors.toSet());
        return ids.stream().distinct().filter(installableIds::contains).map(byId::get).toList();
    }

    /** Everything after the click, in one boundary: whatever throws, the rows settle with a message. */
    private void runInstall(List<SdnCatalogueEntry> batch) {
        try {
            lastInstall = settle(installer.apply(batch));
        } catch (RuntimeException e) {
            log.warn("SDN: installing {} script(s) threw", batch.size(), e);
            lastInstall = new InstallActivity.Failed("Something went wrong while installing: " + e.getMessage());
        } finally {
            batch.forEach(e -> installing.remove(e.id()));
        }
    }

    private InstallActivity settle(SdnInstallResult result) {
        return switch (result) {
            case SdnInstallResult.Installed installed -> new InstallActivity.Finished(register(installed.scripts()));
            case SdnInstallResult.NothingSelected ignored ->
                    new InstallActivity.Failed("Nothing was requested from the launcher.");
            case SdnInstallResult.DeliveryDisabled ignored -> new InstallActivity.Failed(DELIVERY_DISABLED);
            case SdnInstallResult.CourierUnavailable ignored -> new InstallActivity.Failed(
                    "The launcher did not deliver the scripts. Check it is still running, then try again.");
            case SdnInstallResult.Failed failed -> new InstallActivity.Failed("Could not install: " + failed.reason());
        };
    }

    /**
     * Hands the delivered scripts to every live client's runtime, which is what gives
     * them the same controls as a local script. Returns the line to show about it.
     */
    private String register(List<BotScript> scripts) {
        List<Connection> live = liveConnections();
        for (Connection conn : live) {
            scripts.forEach(conn.getRuntime()::registerScript);
        }
        String what = plural(scripts.size(), "script");
        if (live.isEmpty()) {
            return "Installed " + what + ", but no client is connected to load them into. "
                    + "Install again once a client is connected.";
        }
        return "Installed " + what + " on " + plural(live.size(), "client")
                + ". Start them from Installed scripts or a client card.";
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private List<Connection> liveConnections() {
        List<Connection> live = new ArrayList<>();
        for (Connection conn : new ArrayList<>(connections.get())) {
            if (conn.isAlive()) {
                live.add(conn);
            }
        }
        return live;
    }
}
