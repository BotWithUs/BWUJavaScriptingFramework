package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.pages.store.LiveStoreModel;
import com.botwithus.bot.cli.gui.pages.store.RowState;
import com.botwithus.bot.cli.gui.pages.store.StoreQuery;
import com.botwithus.bot.cli.sdn.FavouritesStore;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.runtime.SdnDelivery;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.sdn.StoreCopies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Store page and the picker's "Start a script" list are two screens over one
 * install. Each test drives both through the <em>real</em> {@link SdnInstaller} and
 * ledger, with only the launcher's delivery faked, and checks they agree on whether
 * the script is installed and that the picker starts the script the delivery was for.
 *
 * <p>The shapes are the two the catalogue really has: an entry both agents share,
 * which names the agent-v1 class while this host is delivered the v2 build (class
 * and manifest name both different), and an entry that names no class at all.
 */
class SdnInstallScreensAgreeTest {

    private static final String CLIENT = "BotWithUs_1";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final double MID_JITTER = 0.5;
    private static final int BUILD = 9;

    /** The v2 build the launcher delivers for both shapes. */
    @ScriptManifest(name = "Craft With Us (v2)")
    static final class CraftV2 implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    /** The catalogue shapes under test. */
    enum Shape {
        /** CraftWithUs: shared by both agents, so the catalogue names the agent-v1 class. */
        SHARED_WITH_AGENT_V1("50", "CraftWithUs", "com.example.agentv1.CraftWithUs"),
        /** An entry that names no class. */
        BLANK_CLASS("51", "SkillingWithUs", "");

        final String id;
        final String name;
        final String scriptClass;

        Shape(String id, String name, String scriptClass) {
            this.id = id;
            this.name = name;
            this.scriptClass = scriptClass;
        }

        SdnCatalogueEntry entry() {
            return new SdnCatalogueEntry(id, name, "author", "me", "1.0", "2", "", "", scriptClass,
                    true, true, true, true, null, null, BUILD);
        }
    }

    /** What both screens are asked, in one vocabulary. */
    enum Verdict { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

    @TempDir
    Path home;

    @TempDir
    Path rendezvous;

    private ScriptRuntime runtime;
    private Connection conn;
    private final AtomicInteger deliveries = new AtomicInteger();

    @BeforeEach
    void setUp() {
        PipeClient pipe = mock(PipeClient.class);
        when(pipe.isOpen()).thenReturn(true);
        runtime = new ScriptRuntime(mock(ScriptContext.class));
        conn = new Connection(CLIENT, pipe, mock(RpcClient.class), runtime, new ScriptManagerImpl(runtime));
    }

    @AfterEach
    void tearDown() {
        runtime.stopAll();
    }

    /** Both screens, built the way the host builds them, over one installer and ledger. */
    private record Screens(LiveStoreModel store, LiveSubscriptions picker) {}

    private Screens screens(Shape shape) {
        InstalledScriptsLedger ledger = new InstalledScriptsLedger(home, InstantSource.fixed(NOW));
        SdnInstaller installer = new SdnInstaller(rendezvous, () -> true, this::deliverOneJar, ledger);
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(shape.entry()), false), Runnable::run,
                InstantSource.fixed(NOW), () -> MID_JITTER);
        catalogue.requestNow();
        LiveStoreModel store = new LiveStoreModel(catalogue, installer::install, StoreCopies.of(ledger),
                installer::isDeliveryEnabled, new FavouritesStore(home, Runnable::run), () -> List.of(conn),
                List::of, Runnable::run, InstantSource.fixed(NOW));
        LiveSubscriptions picker = new LiveSubscriptions(() -> List.of(conn), catalogue, installer::install,
                StoreCopies.of(ledger), List::of, Runnable::run);
        return new Screens(store, picker);
    }

    /** The launcher's half: one jar, as for a one-script request, holding the v2 build. */
    private SdnDelivery deliverOneJar() {
        deliveries.incrementAndGet();
        return new SdnDelivery(1, List.of(new SdnDelivery.Jar(0, List.of(new CraftV2()))));
    }

    private static Verdict storeSays(Screens s, Shape shape) {
        RowState state = s.store().view(StoreQuery.DEFAULT).find(shape.id).orElseThrow().state();
        return switch (state) {
            case RowState.NotInstalled ignored -> Verdict.NOT_INSTALLED;
            case RowState.Installed ignored -> Verdict.INSTALLED;
            case RowState.UpdateAvailable ignored -> Verdict.UPDATE_AVAILABLE;
        };
    }

    private static Verdict pickerSays(Screens s) {
        List<SubscriptionEntry> rows = s.picker().group(CLIENT).entries();
        assertEquals(1, rows.size());
        return switch (rows.getFirst().state()) {
            case SubscriptionState.NotInstalled ignored -> Verdict.NOT_INSTALLED;
            case SubscriptionState.Installed ignored -> Verdict.INSTALLED;
            case SubscriptionState.UpdateAvailable ignored -> Verdict.UPDATE_AVAILABLE;
            case SubscriptionState.Installing ignored -> fail("still installing");
            case SubscriptionState.Failed failed -> fail("the picker failed: " + failed.message());
        };
    }

    private ScriptRunner startedV2() {
        ScriptRunner runner = runtime.getRunners().stream()
                .filter(r -> r.getScript().getClass() == CraftV2.class)
                .findFirst()
                .orElseGet(() -> fail("the delivered v2 build is not registered"));
        assertNotNull(runner.lastStartedAt(), "the delivered v2 build must have been started");
        return runner;
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void beforeAnyInstall_bothScreensSayNotInstalled(Shape shape) {
        Screens s = screens(shape);

        assertAll(
                () -> assertEquals(Verdict.NOT_INSTALLED, storeSays(s, shape)),
                () -> assertEquals(Verdict.NOT_INSTALLED, pickerSays(s)));
    }

    /** The reported bug: the picker said "delivered 1 script(s), but not CraftWithUs". */
    @ParameterizedTest
    @EnumSource(Shape.class)
    void installFromThePicker_startsTheDeliveredBuild_andBothScreensSayInstalled(Shape shape) {
        Screens s = screens(shape);

        s.picker().start(CLIENT, shape.id);

        startedV2();
        assertAll(
                () -> assertEquals(Verdict.INSTALLED, pickerSays(s)),
                () -> assertEquals(Verdict.INSTALLED, storeSays(s, shape)));
    }

    /** After a Store install the picker must offer Start, not Install & start, and start that copy. */
    @ParameterizedTest
    @EnumSource(Shape.class)
    void installFromTheStore_bothScreensSayInstalled_andThePickerStartsItWithoutReinstalling(Shape shape) {
        Screens s = screens(shape);

        s.store().install(List.of(shape.id));

        assertAll(
                () -> assertEquals(Verdict.INSTALLED, storeSays(s, shape)),
                () -> assertEquals(Verdict.INSTALLED, pickerSays(s)));
        s.picker().start(CLIENT, shape.id);
        startedV2();
        assertEquals(1, deliveries.get(), "the picker must start the Store's copy, not install it again");
    }
}
