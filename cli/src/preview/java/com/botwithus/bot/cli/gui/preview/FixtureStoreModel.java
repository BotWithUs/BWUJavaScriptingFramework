package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.cli.gui.pages.store.InstallActivity;
import com.botwithus.bot.cli.gui.pages.store.RowState;
import com.botwithus.bot.cli.gui.pages.store.StoreModel;
import com.botwithus.bot.cli.gui.pages.store.StoreQuery;
import com.botwithus.bot.cli.gui.pages.store.StoreRow;
import com.botwithus.bot.cli.gui.pages.store.StoreStatus;
import com.botwithus.bot.cli.gui.pages.store.StoreView;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * DEV ONLY. The Script Store over fixed data, one factory per state the page can
 * be in, so the preview renders each without a launcher. The scripts are the
 * repository's own example scripts; prices, installs and favourites are samples.
 */
final class FixtureStoreModel implements StoreModel {

    private static final Instant SYNCED = LocalDate.of(2026, 9, 26).atTime(LocalTime.of(14, 6))
            .atZone(ZoneId.systemDefault()).toInstant();
    private static final String BWU = "BotWithUs";
    private static final String ME = "Kestrel";
    private static final int BUSY_SIZE = 48;
    private static final int BUSY_FAVOURITE_EVERY = 9;
    private static final int OLD_BUILD = 6;
    private static final int NEW_BUILD = 7;
    private static final int BUSY_STATES = 5;

    private final StoreStatus status;
    private final List<StoreRow> rows;
    private final Set<String> favourites;
    private final boolean canInstall;
    private final AccountStatus line;
    private InstallActivity activity;

    private FixtureStoreModel(StoreStatus status, List<StoreRow> rows, List<String> favourites, boolean canInstall,
                              InstallActivity activity, AccountStatus line) {
        this.status = status;
        this.rows = new ArrayList<>(rows);
        this.favourites = new LinkedHashSet<>(favourites);
        this.canInstall = canInstall;
        this.activity = activity;
        this.line = line;
    }

    // ── States ─────────────────────────────────────────────────────────────

    static FixtureStoreModel signedIn() {
        return ready(false, Optional.empty(), sample(), List.of("woodcutting", "divination", "walk-to-flag"),
                new AccountStatus("Signed in · 3 new", true));
    }

    static FixtureStoreModel noFavourites() {
        return ready(false, Optional.empty(), sample(), List.of(), new AccountStatus("Signed in", true));
    }

    static FixtureStoreModel stale() {
        return ready(true, Optional.empty(), sample(), List.of("woodcutting", "divination"),
                new AccountStatus("Offline · cached", false));
    }

    static FixtureStoreModel refreshFailed() {
        return ready(false, Optional.of("the launcher did not answer"), sample(), List.of("walk-to-flag"),
                new AccountStatus("Signed in", true));
    }

    static FixtureStoreModel emptyCatalogue() {
        return ready(false, Optional.empty(), List.of(), List.of(), new AccountStatus("Signed in", true));
    }

    static FixtureStoreModel busy() {
        List<StoreRow> rows = busyRows();
        List<String> favs = rows.stream().filter(r -> Integer.parseInt(r.id().substring(1)) % BUSY_FAVOURITE_EVERY == 0)
                .map(StoreRow::id).toList();
        return ready(false, Optional.empty(), rows, favs, new AccountStatus("Signed in · 12 new", true));
    }

    static FixtureStoreModel deliveryDisabled() {
        return new FixtureStoreModel(new StoreStatus.Ready(false, SYNCED, Optional.empty()), sample(),
                List.of("woodcutting"), false, new InstallActivity.Idle(), new AccountStatus("Signed in", true));
    }

    /** Two scripts on their way; the rows and the strip both say so. */
    static FixtureStoreModel installing() {
        FixtureStoreModel model = signedIn();
        model.install(List.of("woodcutting-fletcher", "restless-ghost"));
        return model;
    }

    static FixtureStoreModel installFailed() {
        FixtureStoreModel model = signedIn();
        model.activity = new InstallActivity.Failed(
                "The launcher did not deliver the scripts. Check it is still running, then try again.");
        return model;
    }

    static FixtureStoreModel installed() {
        FixtureStoreModel model = signedIn();
        model.activity = new InstallActivity.Finished(
                "Installed 2 scripts on 6 clients. Start them from Installed scripts or a client card.");
        return model;
    }

    static FixtureStoreModel loading() {
        return new FixtureStoreModel(new StoreStatus.Loading(), List.of(), List.of(), true,
                new InstallActivity.Idle(), new AccountStatus("Syncing…", false));
    }

    static FixtureStoreModel launcherNotRunning() {
        return unavailable(StoreStatus.Reason.LAUNCHER_NOT_RUNNING, "", "Launcher not running");
    }

    static FixtureStoreModel signedOut() {
        return unavailable(StoreStatus.Reason.SIGNED_OUT, "", "Signed out");
    }

    static FixtureStoreModel noSubscription() {
        return unavailable(StoreStatus.Reason.NO_SUBSCRIPTION, "", "Subscription needed");
    }

    static FixtureStoreModel failed() {
        return unavailable(StoreStatus.Reason.FAILED, "The launcher sent a reply this host could not read.",
                "Store unavailable");
    }

    private static FixtureStoreModel ready(boolean stale, Optional<String> error, List<StoreRow> rows,
                                           List<String> favourites, AccountStatus line) {
        return new FixtureStoreModel(new StoreStatus.Ready(stale, SYNCED, error), rows, favourites, true,
                new InstallActivity.Idle(), line);
    }

    private static FixtureStoreModel unavailable(StoreStatus.Reason reason, String detail, String line) {
        return new FixtureStoreModel(new StoreStatus.Unavailable(new StoreStatus.Notice(reason, detail)), List.of(),
                List.of(), true, new InstallActivity.Idle(), new AccountStatus(line, false));
    }

    // ── StoreModel ─────────────────────────────────────────────────────────

    @Override
    public StoreView view(StoreQuery query) {
        List<StoreRow> shown = rows.stream().map(r -> r.withFavourite(favourites.contains(r.id()))).toList();
        long inFlight = rows.stream().filter(StoreRow::isInstalling).count();
        InstallActivity now = inFlight > 0 ? new InstallActivity.Installing((int) inFlight) : activity;
        return StoreView.of(status, query, shown, favourites, false, canInstall, now);
    }

    @Override
    public void refresh() {
    }

    @Override
    public void toggleFavourite(String id) {
        if (!favourites.remove(id)) {
            favourites.add(id);
        }
    }

    @Override
    public void install(List<String> ids) {
        rows.replaceAll(r -> ids.contains(r.id()) && r.isInstallable() ? r.withInstalling(true) : r);
    }

    @Override
    public void dismissInstallMessage() {
        activity = new InstallActivity.Idle();
    }

    @Override
    public void markSeen() {
    }

    @Override
    public AccountStatus signInLine() {
        return line;
    }

    // ── Data ───────────────────────────────────────────────────────────────

    private static List<StoreRow> sample() {
        return List.of(
                row("woodcutting", "Woodcutting", BWU, ScriptCategory.WOODCUTTING, Pricing.PAID, "4.99 per month",
                        "2.1", "Chops a configurable tree at a configurable spot; banks, drops, or wood-boxes the logs.",
                        "Pick the tree, the spot and what happens to the logs. Supports stop conditions by level, "
                                + "log count or time, and ships its own in-game panel.",
                        new RowState.UpdateAvailable(OLD_BUILD, NEW_BUILD), true),
                row("woodcutting-fletcher", "Woodcutting Fletcher", BWU, ScriptCategory.WOODCUTTING, Pricing.FREE, "",
                        "1.0", "Chops trees and drops logs when full.",
                        "A minimal power-chopper: chop until the backpack is full, drop the logs, repeat.",
                        new RowState.NotInstalled(false), true),
                row("divination", "Divination", BWU, ScriptCategory.DIVINATION, Pricing.PAID, "19.99 once", "1.0",
                        "Harvests wisps and converts memories at the nearest divination spot.",
                        "Finds the nearest wisp colony for your level, harvests until full and converts memories.",
                        new RowState.Installed(), true),
                row("cooks-assistant", "Cook's Assistant", BWU, ScriptCategory.QUESTING, Pricing.FREE, "", "1.0",
                        "Solves Cook's Assistant end-to-end.",
                        "Gathers the egg, milk and flour, then hands them to the cook in Lumbridge Castle.",
                        new RowState.Installed(), true),
                row("restless-ghost", "The Restless Ghost", BWU, ScriptCategory.QUESTING, Pricing.FREE, "", "0.1",
                        "Solves The Restless Ghost end-to-end.",
                        "Talks to Father Aereck and Father Urhney, finds the skull and lays the ghost to rest.",
                        new RowState.NotInstalled(true), true),
                row("witchs-potion", "Witch's Potion", BWU, ScriptCategory.QUESTING, Pricing.UNKNOWN, "", "0.1",
                        "Solves Witch's Potion end-to-end.",
                        "Collects the ingredients for Hetty in Rimmington and drinks from the cauldron.",
                        new RowState.NotInstalled(false), true),
                row("walk-to-flag", "Walk to Flag", BWU, ScriptCategory.UTILITY, Pricing.FREE, "", "1.0",
                        "Walks to the world-map flag using world pathfinding.",
                        "Set a flag on the world map and the script walks you there, across regions.",
                        new RowState.Installed(), true),
                owned(row("location-probe", "Location Probe", ME, ScriptCategory.UTILITY, Pricing.FREE, "", "1.0",
                        "Logs snapshot.locations() count and sample rows each tick.",
                        "A debugging aid that prints what the scene snapshot sees. Built for the older agent.",
                        new RowState.NotInstalled(false), false)),
                owned(row("example-script", "Example Script", ME, ScriptCategory.UTILITY, Pricing.FREE, "", "1.0",
                        "A demo script showing the entity query API and Live Config.",
                        "The starter template: queries nearby entities and exposes a few Live Config fields.",
                        new RowState.Installed(), true)));
    }

    private static StoreRow row(String id, String name, String author, ScriptCategory category, Pricing pricing,
                                String price, String version, String tagline, String description, RowState state,
                                boolean runsHere) {
        return new StoreRow(id, name, author, tagline, description, category, pricing, price, version,
                "com.botwithus.scripts." + id.replace("-", ""), runsHere, !runsHere, false, state, false, false);
    }

    private static StoreRow owned(StoreRow r) {
        return new StoreRow(r.id(), r.name(), r.author(), r.summary(), r.description(), r.category(), r.pricing(),
                r.priceText(), r.storeVersion(), r.scriptClass(), r.runsHere(), true, true, r.state(), false, false);
    }

    /** A catalogue big enough to scroll: every category, state and price in turn. */
    private static List<StoreRow> busyRows() {
        ScriptCategory[] categories = ScriptCategory.values();
        Pricing[] prices = Pricing.values();
        List<StoreRow> out = new ArrayList<>();
        for (int i = 0; i < BUSY_SIZE; i++) {
            ScriptCategory category = categories[i % categories.length];
            Pricing pricing = prices[i % prices.length];
            String name = category.getDisplayName() + " " + (i < categories.length ? "Pro" : "Lite");
            out.add(row("s" + i, name, i % 2 == 0 ? BWU : "Oakheart", category, pricing,
                    pricing == Pricing.PAID ? "2.99 per week" : "", "1." + i,
                    "Trains " + category.getDisplayName().toLowerCase(Locale.ROOT) + " at the best spot for your level.", "",
                    busyState(i), i % BUSY_STATES != BUSY_STATES - 1));
        }
        return out;
    }

    private static RowState busyState(int i) {
        return switch (i % BUSY_STATES) {
            case 0 -> new RowState.Installed();
            case 1 -> new RowState.UpdateAvailable(i, i + 1);
            default -> new RowState.NotInstalled(false);
        };
    }
}
