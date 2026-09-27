package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.nav.SecondLine.AccountStatus;
import com.botwithus.bot.cli.sdn.FavouritesStore;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstallResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the Store's model over a real catalogue refresher, favourites store,
 * ledger and script runtime. Only the launcher is replaced: the catalogue comes
 * from a supplier, and installs from a function that hands back a delivery.
 */
class LiveStoreModelTest {

    private static final String ME = "me";
    private static final Instant NOW = Instant.parse("2026-09-26T14:06:00Z");
    private static final double MID_JITTER = 0.5;
    private static final int OLD_BUILD = 6;
    private static final int NEW_BUILD = 7;

    // ── Scripts the host can have loaded ───────────────────────────────────

    @ScriptManifest(name = "Local Build")
    static final class LocalBuild implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Store Behind")
    static final class StoreBehind implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Store Current")
    static final class StoreCurrent implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Not Here")
    static final class NotHere implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    // ── Catalogue entries ──────────────────────────────────────────────────

    /** A v2 script, free, uncategorised, by someone else, with no build. */
    private static SdnCatalogueEntry entry(String id, String name, Class<? extends BotScript> cls) {
        return new SdnCatalogueEntry(id, name, "author", ME, "1.0", "2", "tag " + name, "", className(cls),
                false, true, true, true, null, null, null);
    }

    private static String className(Class<? extends BotScript> cls) {
        return cls == null ? "" : cls.getName();
    }

    private static SdnCatalogueEntry with(SdnCatalogueEntry e, String author, Boolean isFree, String category,
                                          boolean v2, Integer build) {
        return new SdnCatalogueEntry(e.id(), e.name(), author, e.subscriber(), e.version(), e.apiVersion(),
                e.tagline(), e.description(), e.scriptClass(), !v2, v2, e.subscribed(), isFree, category,
                e.price(), build);
    }

    private static SdnCatalogueEntry priced(SdnCatalogueEntry e, Boolean isFree) {
        return with(e, e.author(), isFree, e.category(), e.agentv2Support(), e.currentBuild());
    }

    private static SdnCatalogueEntry categorised(SdnCatalogueEntry e, String slug) {
        return with(e, e.author(), e.isFree(), slug, e.agentv2Support(), e.currentBuild());
    }

    private static SdnCatalogueEntry byAuthor(SdnCatalogueEntry e, String author) {
        return with(e, author, e.isFree(), e.category(), e.agentv2Support(), e.currentBuild());
    }

    private static SdnCatalogueEntry olderAgentOnly(SdnCatalogueEntry e) {
        return with(e, e.author(), e.isFree(), e.category(), false, e.currentBuild());
    }

    private static SdnCatalogueEntry build(SdnCatalogueEntry e, int currentBuild) {
        return with(e, e.author(), e.isFree(), e.category(), e.agentv2Support(), currentBuild);
    }

    // ── Host ───────────────────────────────────────────────────────────────

    @TempDir
    Path home;

    private final AtomicReference<SdnCatalogueResult> launcher = new AtomicReference<>();
    private final List<List<SdnCatalogueEntry>> installRequests = new ArrayList<>();
    private final Deque<Runnable> pendingInstalls = new ArrayDeque<>();
    private final Executor queuedInstalls = pendingInstalls::add;
    private Function<List<SdnCatalogueEntry>, SdnInstallResult> launcherInstall;
    private final List<BotScript> localScripts = new ArrayList<>();
    private boolean deliveryEnabled = true;

    private ScriptRuntime runtime;
    private Connection conn;
    private InstalledScriptsLedger ledger;
    private FavouritesStore favourites;
    private SdnCatalogueRefresher refresher;

    @BeforeEach
    void setUp() {
        PipeClient pipe = mock(PipeClient.class);
        when(pipe.isOpen()).thenReturn(true);
        runtime = new ScriptRuntime(mock(ScriptContext.class));
        conn = new Connection("BotWithUs_1", pipe, mock(RpcClient.class), runtime, new ScriptManagerImpl(runtime));
        ledger = new InstalledScriptsLedger(home, InstantSource.fixed(NOW));
        favourites = new FavouritesStore(home, Runnable::run);
        refresher = new SdnCatalogueRefresher(launcher::get, Runnable::run, InstantSource.fixed(NOW),
                () -> MID_JITTER);
        launcherInstall = entries -> new SdnInstallResult.Installed(List.of());
    }

    @AfterEach
    void tearDown() {
        runtime.stopAll();
    }

    private LiveStoreModel model() {
        return new LiveStoreModel(refresher, entries -> {
            installRequests.add(List.copyOf(entries));
            return launcherInstall.apply(entries);
        }, ledger::find, () -> deliveryEnabled, favourites, () -> List.of(conn), () -> localScripts,
                queuedInstalls, InstantSource.fixed(NOW));
    }

    /** The launcher answers {@code result} to the next fetch, which runs at once. */
    private void launcherAnswers(SdnCatalogueResult result) {
        launcher.set(result);
        refresher.requestNow();
    }

    private void catalogue(SdnCatalogueEntry... entries) {
        launcherAnswers(new SdnCatalogueResult.Delivered(List.of(entries), false));
    }

    private static List<String> ids(List<StoreRow> rows) {
        return rows.stream().map(StoreRow::id).toList();
    }

    private void storeInstalled(Class<? extends BotScript> cls, String catalogueId, int build) throws IOException {
        SdnCatalogueEntry recorded = build(entry(catalogueId, "recorded", cls), build);
        ledger.record(Map.of(cls.getName(), recorded));
    }

    private void runDeferredInstalls() {
        while (!pendingInstalls.isEmpty()) {
            pendingInstalls.poll().run();
        }
    }

    // ── Views and their counts ─────────────────────────────────────────────

    @Nested
    class Views {

        /** One script in each state this host can be in. */
        @BeforeEach
        void everyState() throws IOException {
            localScripts.add(new LocalBuild());
            runtime.registerScript(new StoreBehind());
            runtime.registerScript(new StoreCurrent());
            storeInstalled(StoreBehind.class, "behind", OLD_BUILD);
            storeInstalled(StoreCurrent.class, "current", NEW_BUILD);
            catalogue(
                    entry("local", "Local Build", LocalBuild.class),
                    build(entry("behind", "Store Behind", StoreBehind.class), NEW_BUILD),
                    build(entry("current", "Store Current", StoreCurrent.class), NEW_BUILD),
                    entry("missing", "Not Here", NotHere.class),
                    olderAgentOnly(entry("older", "Older Agent", null)));
        }

        @Test
        void counts_coverEveryView() {
            StoreCounts counts = model().view(StoreQuery.DEFAULT).counts();

            assertAll(
                    () -> assertEquals(5, counts.all()),
                    () -> assertEquals(3, counts.installed(), "an update-available copy is still installed"),
                    () -> assertEquals(1, counts.updates()),
                    () -> assertEquals(2, counts.notInstalled()));
        }

        @Test
        void eachView_listsItsScripts() {
            LiveStoreModel model = model();

            assertAll(
                    () -> assertEquals(Set.of("local", "behind", "current", "missing", "older"),
                            Set.copyOf(ids(model.view(StoreQuery.DEFAULT.withTab(StoreTab.ALL)).listed()))),
                    () -> assertEquals(Set.of("local", "behind", "current"),
                            Set.copyOf(ids(model.view(StoreQuery.DEFAULT.withTab(StoreTab.INSTALLED)).listed()))),
                    () -> assertEquals(List.of("behind"),
                            ids(model.view(StoreQuery.DEFAULT.withTab(StoreTab.UPDATES)).listed())),
                    () -> assertEquals(Set.of("missing", "older"), Set.copyOf(
                            ids(model.view(StoreQuery.DEFAULT.withTab(StoreTab.NOT_INSTALLED)).listed()))));
        }

        @Test
        void rowStates_comeFromLocalCopiesRunnersAndTheLedger() {
            StoreView view = model().view(StoreQuery.DEFAULT);

            assertAll(
                    () -> assertInstanceOf(RowState.Installed.class, view.find("local").orElseThrow().state(),
                            "a local build is installed, and never badged for an update"),
                    () -> assertEquals(new RowState.UpdateAvailable(OLD_BUILD, NEW_BUILD),
                            view.find("behind").orElseThrow().state()),
                    () -> assertInstanceOf(RowState.Installed.class, view.find("current").orElseThrow().state()),
                    () -> assertEquals(new RowState.NotInstalled(false), view.find("missing").orElseThrow().state()),
                    () -> assertFalse(view.find("older").orElseThrow().runsHere()));
        }
    }

    @Test
    void aStoreInstallThatIsNoLongerLoaded_isNotInstalledButRemembered() throws IOException {
        storeInstalled(StoreBehind.class, "behind", OLD_BUILD);
        catalogue(entry("behind", "Store Behind", StoreBehind.class));

        StoreRow row = model().view(StoreQuery.DEFAULT).find("behind").orElseThrow();

        assertEquals(new RowState.NotInstalled(true), row.state());
        assertTrue(row.isInstallable(), "a restart unloaded it, so it can be installed again");
    }

    @Test
    void aBlankScriptClass_neverAdoptsALoadedScript() {
        localScripts.add(new LocalBuild());
        catalogue(entry("anon", "Local Build", null));

        assertEquals(new RowState.NotInstalled(false),
                model().view(StoreQuery.DEFAULT).find("anon").orElseThrow().state());
    }

    // ── Filters ────────────────────────────────────────────────────────────

    @Nested
    class Filters {

        @Test
        void price_unknownPricingMatchesNeitherFreeNorPaid() {
            catalogue(priced(entry("free", "Free One", null), true),
                    priced(entry("paid", "Paid One", null), false),
                    priced(entry("unknown", "Unknown One", null), null));
            LiveStoreModel model = model();

            assertAll(
                    () -> assertEquals(List.of("free"),
                            ids(model.view(StoreQuery.DEFAULT.withPrice(PriceFilter.FREE)).listed())),
                    () -> assertEquals(List.of("paid"),
                            ids(model.view(StoreQuery.DEFAULT.withPrice(PriceFilter.PAID)).listed())),
                    () -> assertEquals(3, model.view(StoreQuery.DEFAULT.withPrice(PriceFilter.ANY)).listed().size()),
                    () -> assertEquals(1, model.view(StoreQuery.DEFAULT).counts().free()),
                    () -> assertEquals(1, model.view(StoreQuery.DEFAULT).counts().paid()));
        }

        @Test
        void category_showsOnlyTheTickedCategories() {
            catalogue(categorised(entry("wc", "Chopper", null), "woodcutting"),
                    categorised(entry("fish", "Fisher", null), "fishing"),
                    categorised(entry("q", "Odd Jobs", null), "misc"));
            LiveStoreModel model = model();
            StoreQuery woodcutting = StoreQuery.DEFAULT.withCategoryToggled(ScriptCategory.WOODCUTTING);

            assertAll(
                    () -> assertEquals(List.of("wc"), ids(model.view(woodcutting).listed())),
                    () -> assertEquals(Set.of("wc", "fish"), Set.copyOf(ids(model.view(
                            woodcutting.withCategoryToggled(ScriptCategory.FISHING)).listed()))),
                    () -> assertEquals(3, model.view(woodcutting.withCategoryToggled(ScriptCategory.WOODCUTTING))
                            .listed().size(), "unticking the last category shows them all again"),
                    () -> assertEquals(List.of(ScriptCategory.FISHING, ScriptCategory.OTHER,
                            ScriptCategory.WOODCUTTING), model.view(StoreQuery.DEFAULT).categories()));
        }

        @Test
        void runsHere_hidesOlderAgentOnlyScripts() {
            catalogue(entry("v2", "New", null), olderAgentOnly(entry("v1", "Old", null)));

            assertEquals(List.of("v2"), ids(model().view(StoreQuery.DEFAULT.withRunsHereOnly(true)).listed()));
        }

        @Test
        void madeByYou_showsOnlyTheAccountsOwnScripts() {
            catalogue(byAuthor(entry("mine", "Mine", null), "ME"), entry("theirs", "Theirs", null));

            assertEquals(List.of("mine"), ids(model().view(StoreQuery.DEFAULT.withMadeByYouOnly(true)).listed()));
        }

        @Test
        void search_matchesNameAuthorOrSummary_ignoringCase() {
            catalogue(entry("a", "Woodcutting", null), byAuthor(entry("b", "Other", null), "Wood Lord"),
                    entry("c", "Fisher", null));
            LiveStoreModel model = model();

            assertAll(
                    () -> assertEquals(Set.of("a", "b"),
                            Set.copyOf(ids(model.view(StoreQuery.DEFAULT.withSearch("  WOOD ")).listed()))),
                    () -> assertEquals(List.of("c"),
                            ids(model.view(StoreQuery.DEFAULT.withSearch("tag fish")).listed())));
        }

        @Test
        void clearFilters_appearsForAnyFilter_andShowsEverythingAgain() {
            catalogue(priced(entry("a", "A", null), true), priced(entry("b", "B", null), false));
            StoreQuery narrowed = StoreQuery.DEFAULT.withTab(StoreTab.INSTALLED).withPrice(PriceFilter.FREE)
                    .withCategoryToggled(ScriptCategory.UTILITY).withRunsHereOnly(true).withMadeByYouOnly(true)
                    .withSearch("x").withSort(StoreSort.AUTHOR);

            assertAll(
                    () -> assertFalse(StoreQuery.DEFAULT.hasActiveFilters()),
                    () -> assertFalse(StoreQuery.DEFAULT.withTab(StoreTab.UPDATES).withSort(StoreSort.NAME)
                            .hasActiveFilters(), "the view and the order are not filters"),
                    () -> assertTrue(StoreQuery.DEFAULT.withPrice(PriceFilter.PAID).hasActiveFilters()),
                    () -> assertTrue(StoreQuery.DEFAULT.withCategoryToggled(ScriptCategory.UTILITY)
                            .hasActiveFilters()),
                    () -> assertTrue(StoreQuery.DEFAULT.withRunsHereOnly(true).hasActiveFilters()),
                    () -> assertTrue(StoreQuery.DEFAULT.withMadeByYouOnly(true).hasActiveFilters()),
                    () -> assertTrue(StoreQuery.DEFAULT.withSearch("x").hasActiveFilters()),
                    () -> assertTrue(model().view(narrowed).listed().isEmpty()),
                    () -> assertEquals(2, model().view(narrowed.cleared()).listed().size()),
                    () -> assertEquals(StoreSort.AUTHOR, narrowed.cleared().sort(), "clearing keeps the order"));
        }
    }

    // ── Order and favourites ───────────────────────────────────────────────

    @Nested
    class Order {

        @BeforeEach
        void threeScripts() {
            catalogue(byAuthor(entry("c", "charlie", null), "Zed"),
                    byAuthor(entry("a", "Alpha", null), "Mia"),
                    byAuthor(entry("b", "bravo", null), "mia"));
        }

        @Test
        void name_isAlphabeticalIgnoringCase() {
            assertEquals(List.of("a", "b", "c"),
                    ids(model().view(StoreQuery.DEFAULT.withSort(StoreSort.NAME)).listed()));
        }

        @Test
        void author_thenName() {
            assertEquals(List.of("a", "b", "c"),
                    ids(model().view(StoreQuery.DEFAULT.withSort(StoreSort.AUTHOR)).listed()));
            catalogue(byAuthor(entry("c", "charlie", null), "Abe"), byAuthor(entry("a", "Alpha", null), "Mia"));
            assertEquals(List.of("c", "a"),
                    ids(model().view(StoreQuery.DEFAULT.withSort(StoreSort.AUTHOR)).listed()));
        }

        @Test
        void favouritesFirst_putsStarredScriptsAhead_thenByName() {
            LiveStoreModel model = model();
            model.toggleFavourite("c");

            assertEquals(List.of("c", "a", "b"), ids(model.view(StoreQuery.DEFAULT).listed()));
        }

        @Test
        void favourites_ignoreEveryFilter_keepStarringOrder_andPersist() {
            LiveStoreModel model = model();
            model.toggleFavourite("b");
            model.toggleFavourite("a");
            StoreQuery hidesEverything = StoreQuery.DEFAULT.withSearch("nothing matches this");

            StoreView view = model.view(hidesEverything);

            assertAll(
                    () -> assertTrue(view.listed().isEmpty()),
                    () -> assertEquals(List.of("b", "a"), ids(view.favourites())),
                    () -> assertTrue(view.find("a").orElseThrow().isFavourite()),
                    () -> assertTrue(new FavouritesStore(home, Runnable::run).isFavourite("a"),
                            "a star is saved"));
        }

        @Test
        void toggleFavourite_twice_unstars() {
            LiveStoreModel model = model();
            model.toggleFavourite("a");
            model.toggleFavourite("a");

            assertTrue(model.view(StoreQuery.DEFAULT).favourites().isEmpty());
            assertFalse(model.view(StoreQuery.DEFAULT).find("a").orElseThrow().isFavourite());
        }
    }

    // ── Selection and the batch install ────────────────────────────────────

    @Nested
    class Install {

        @BeforeEach
        void catalogueWithEveryKindOfRow() throws IOException {
            runtime.registerScript(new StoreCurrent());
            storeInstalled(StoreCurrent.class, "current", NEW_BUILD);
            runtime.registerScript(new StoreBehind());
            storeInstalled(StoreBehind.class, "behind", OLD_BUILD);
            catalogue(entry("one", "One", NotHere.class),
                    build(entry("behind", "Behind", StoreBehind.class), NEW_BUILD),
                    build(entry("current", "Current", StoreCurrent.class), NEW_BUILD),
                    olderAgentOnly(entry("older", "Older", null)),
                    entry("two", "Two", null));
        }

        @Test
        void selection_onlyTicksInstallableRows() {
            StoreView view = model().view(StoreQuery.DEFAULT);
            StoreSelection selection = new StoreSelection();

            view.all().forEach(selection::toggle);

            assertEquals(List.of("one", "behind", "two"), selection.ids(),
                    "installed-and-current and older-agent-only rows cannot be ticked");
        }

        @Test
        void installingTheSelection_sendsExactlyThoseEntries_inTickOrder() {
            LiveStoreModel model = model();
            StoreView view = model.view(StoreQuery.DEFAULT);
            StoreSelection selection = new StoreSelection();
            selection.toggle(view.find("two").orElseThrow());
            selection.toggle(view.find("behind").orElseThrow());

            model.install(selection.ids());
            runDeferredInstalls();

            assertEquals(1, installRequests.size());
            assertEquals(List.of("two", "behind"), installRequests.getFirst().stream()
                    .map(SdnCatalogueEntry::id).toList());
        }

        @Test
        void install_leavesOutWhatCannotBeInstalled() {
            LiveStoreModel model = model();

            model.install(List.of("current", "older", "gone", "one"));
            runDeferredInstalls();

            assertEquals(List.of("one"), installRequests.getFirst().stream().map(SdnCatalogueEntry::id).toList());
        }

        @Test
        void install_nothingInstallable_asksTheLauncherForNothing() {
            LiveStoreModel model = model();

            model.install(List.of("current", "older"));
            runDeferredInstalls();

            assertTrue(installRequests.isEmpty());
            assertInstanceOf(InstallActivity.Idle.class, model.view(StoreQuery.DEFAULT).install());
        }

        @Test
        void whileInFlight_rowsSayInstalling_andCannotBeTickedAgain() {
            LiveStoreModel model = model();
            model.install(List.of("one", "two"));

            StoreView during = model.view(StoreQuery.DEFAULT);

            assertAll(
                    () -> assertTrue(installRequests.isEmpty(), "the launcher is asked off the render thread"),
                    () -> assertTrue(during.find("one").orElseThrow().isInstalling()),
                    () -> assertFalse(during.find("one").orElseThrow().isInstallable()),
                    () -> assertEquals(new InstallActivity.Installing(2), during.install()));
        }

        @Test
        void aDelivery_isRegisteredOnLiveClients_andTheRowBecomesInstalled() {
            BotScript delivered = new NotHere();
            launcherInstall = entries -> new SdnInstallResult.Installed(List.of(delivered));
            LiveStoreModel model = model();

            model.install(List.of("one"));
            runDeferredInstalls();
            StoreView after = model.view(StoreQuery.DEFAULT);

            assertAll(
                    () -> assertSame(delivered, runtime.findRunner("Not Here").getScript()),
                    () -> assertInstanceOf(RowState.Installed.class, after.find("one").orElseThrow().state()),
                    () -> assertFalse(after.find("one").orElseThrow().isInstalling()),
                    () -> assertTrue(assertInstanceOf(InstallActivity.Finished.class, after.install()).message()
                            .contains("1 client")));
        }

        @Test
        void aFailedInstall_saysWhy_andFreesTheRows() {
            launcherInstall = entries -> new SdnInstallResult.CourierUnavailable();
            LiveStoreModel model = model();

            model.install(List.of("one"));
            runDeferredInstalls();
            StoreView after = model.view(StoreQuery.DEFAULT);

            assertTrue(assertInstanceOf(InstallActivity.Failed.class, after.install()).message()
                    .contains("launcher"));
            assertTrue(after.find("one").orElseThrow().isInstallable());
        }

        @Test
        void anInstallThatThrows_stillSettles() {
            launcherInstall = entries -> {
                throw new IllegalStateException("disk full");
            };
            LiveStoreModel model = model();

            model.install(List.of("one"));
            runDeferredInstalls();

            assertTrue(assertInstanceOf(InstallActivity.Failed.class, model.view(StoreQuery.DEFAULT).install())
                    .message().contains("disk full"));
        }

        @Test
        void deliveryDisabled_neverAsksTheLauncher_andSaysHowToFixIt() {
            deliveryEnabled = false;
            LiveStoreModel model = model();

            model.install(List.of("one"));
            runDeferredInstalls();
            StoreView view = model.view(StoreQuery.DEFAULT);

            assertAll(
                    () -> assertTrue(installRequests.isEmpty()),
                    () -> assertFalse(view.canInstall()),
                    () -> assertTrue(assertInstanceOf(InstallActivity.Failed.class, view.install()).message()
                            .contains("launcher")));
        }

        @Test
        void dismiss_clearsTheLastInstallLine() {
            launcherInstall = entries -> new SdnInstallResult.CourierUnavailable();
            LiveStoreModel model = model();
            model.install(List.of("one"));
            runDeferredInstalls();

            model.dismissInstallMessage();

            assertInstanceOf(InstallActivity.Idle.class, model.view(StoreQuery.DEFAULT).install());
        }
    }

    // ── What the launcher answered ─────────────────────────────────────────

    @Nested
    class LauncherStates {

        @Test
        void beforeAnyAnswer_itIsLoading() {
            SdnCatalogueRefresher neverAnswers = new SdnCatalogueRefresher(launcher::get, task -> { },
                    InstantSource.fixed(NOW), () -> MID_JITTER);
            refresher = neverAnswers;
            LiveStoreModel model = model();

            assertAll(
                    () -> assertInstanceOf(StoreStatus.Loading.class, model.view(StoreQuery.DEFAULT).status()),
                    () -> assertEquals(new AccountStatus("Syncing…", false), model.signInLine()));
        }

        @Test
        void aFreshCatalogue_isReady() {
            catalogue(entry("a", "A", null));

            StoreStatus.Ready ready = assertInstanceOf(StoreStatus.Ready.class,
                    model().view(StoreQuery.DEFAULT).status());

            assertFalse(ready.isStale());
            assertEquals(NOW, ready.syncedAt());
            assertTrue(ready.refreshError().isEmpty());
        }

        @Test
        void aStaleCatalogue_isListedAndMarkedStale() {
            launcherAnswers(new SdnCatalogueResult.Delivered(List.of(entry("a", "A", null)), true));
            LiveStoreModel model = model();
            StoreView view = model.view(StoreQuery.DEFAULT);

            assertTrue(assertInstanceOf(StoreStatus.Ready.class, view.status()).isStale());
            assertEquals(1, view.all().size());
            assertEquals(new AccountStatus("Offline · cached", false), model.signInLine());
        }

        @Test
        void aFailedRefresh_keepsTheListAndSaysWhy() {
            catalogue(entry("a", "A", null));
            launcherAnswers(new SdnCatalogueResult.CourierUnavailable());

            StoreView view = model().view(StoreQuery.DEFAULT);

            Optional<String> error = assertInstanceOf(StoreStatus.Ready.class, view.status()).refreshError();
            assertTrue(error.isPresent());
            assertEquals(1, view.all().size());
        }

        @Test
        void eachFailure_isItsOwnNotice_withItsOwnSidebarLine() {
            assertAll(
                    () -> assertNotice(new SdnCatalogueResult.CourierUnavailable(),
                            new StoreStatus.Notice(StoreStatus.Reason.LAUNCHER_NOT_RUNNING, ""),
                            new AccountStatus("Launcher not running", false)),
                    () -> assertNotice(new SdnCatalogueResult.NotSignedIn(),
                            new StoreStatus.Notice(StoreStatus.Reason.SIGNED_OUT, ""),
                            new AccountStatus("Signed out", false)),
                    () -> assertNotice(new SdnCatalogueResult.SubscriptionRequired(),
                            new StoreStatus.Notice(StoreStatus.Reason.NO_SUBSCRIPTION, ""),
                            new AccountStatus("Subscription needed", false)),
                    () -> assertNotice(new SdnCatalogueResult.Failed("bad reply"),
                            new StoreStatus.Notice(StoreStatus.Reason.FAILED, "bad reply"),
                            new AccountStatus("Store unavailable", false)));
        }

        private void assertNotice(SdnCatalogueResult answer, StoreStatus.Notice expected, AccountStatus line) {
            refresher = new SdnCatalogueRefresher(launcher::get, Runnable::run, InstantSource.fixed(NOW),
                    () -> MID_JITTER);
            launcherAnswers(answer);
            LiveStoreModel model = model();
            StoreView view = model.view(StoreQuery.DEFAULT);

            assertEquals(new StoreStatus.Unavailable(expected), view.status());
            assertTrue(view.all().isEmpty());
            assertEquals(line, model.signInLine());
        }

        @Test
        void refresh_asksTheLauncherAgain() {
            catalogue(entry("a", "A", null));
            LiveStoreModel model = model();
            launcher.set(new SdnCatalogueResult.Delivered(List.of(entry("a", "A", null), entry("b", "B", null)),
                    false));

            model.refresh();

            assertEquals(2, model.view(StoreQuery.DEFAULT).all().size());
        }
    }

    // ── New scripts ────────────────────────────────────────────────────────

    @Nested
    class NewScripts {

        @Test
        void theFirstCatalogueEverOpened_isNotAllNew() {
            catalogue(entry("a", "A", null), entry("b", "B", null));

            assertEquals(new AccountStatus("Signed in", true), model().signInLine());
        }

        @Test
        void scriptsAddedSinceTheStoreWasLastOpened_areNew_untilItIsOpenedAgain() {
            catalogue(entry("a", "A", null));
            LiveStoreModel model = model();
            model.markSeen();
            catalogue(entry("a", "A", null), entry("b", "B", null), entry("c", "C", null));

            AccountStatus before = model.signInLine();
            model.markSeen();

            assertEquals(new AccountStatus("Signed in · 2 new", true), before);
            assertEquals(new AccountStatus("Signed in", true), model.signInLine());
            assertEquals(List.of(), new FavouritesStore(home, Runnable::run).unseen(List.of("a", "b", "c")),
                    "what was seen is saved");
        }

        @Test
        void markSeen_beforeAnyCatalogue_setsNoBaseline() {
            refresher = new SdnCatalogueRefresher(launcher::get, task -> { }, InstantSource.fixed(NOW),
                    () -> MID_JITTER);

            model().markSeen();

            assertEquals(List.of(), favourites.unseen(List.of("a")));
        }
    }
}
