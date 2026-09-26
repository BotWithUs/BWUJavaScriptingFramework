package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstallResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the picker's install-and-start path through a real {@link ScriptRuntime},
 * because the defect it guards lives in how the runtime keys runners by name.
 * The launcher is replaced by an install function that hands back a delivery.
 */
class LiveSubscriptionsTest {

    private static final String CLIENT = "BotWithUs_1";
    private static final String SCRIPT_ID = "41";
    private static final String SHARED_NAME = "Woodcutting";
    private static final double MID_JITTER = 0.5;

    /** A local script the user installed themselves, under the same name as the subscription. */
    @ScriptManifest(name = SHARED_NAME)
    static final class LocalWoodcutting implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    /** What the launcher delivers for the subscription. */
    @ScriptManifest(name = SHARED_NAME)
    static final class DeliveredWoodcutting implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    private ScriptRuntime runtime;
    private Connection conn;

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

    private static SdnCatalogueEntry entry(String scriptClass) {
        return new SdnCatalogueEntry(SCRIPT_ID, SHARED_NAME, "author", "me", "1.0", "2", "", "", scriptClass,
                false, true, true, false);
    }

    private static SdnCatalogueRefresher catalogue(SdnCatalogueEntry entry) {
        SdnCatalogueRefresher refresher = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(entry), false), Runnable::run,
                InstantSource.system(), () -> MID_JITTER);
        refresher.requestNow();
        return refresher;
    }

    private static Function<List<String>, SdnInstallResult> delivers(BotScript script) {
        return ids -> new SdnInstallResult.Installed(List.of(script));
    }

    private LiveSubscriptions subscriptions(Supplier<? extends Collection<Connection>> connections,
                                            SdnCatalogueEntry entry, BotScript delivery,
                                            List<LocalScript> locals) {
        return new LiveSubscriptions(connections, catalogue(entry), delivers(delivery), () -> locals, Runnable::run);
    }

    private static SubscriptionEntry onlyRow(LiveSubscriptions subs) {
        List<SubscriptionEntry> rows = subs.group(CLIENT).entries();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    // ── Fix 1: a same-named script already loaded is never started instead ──

    @Test
    void start_deliveryNamedLikeALoadedLocalScript_doesNotStartTheLocalOne() {
        ScriptRunner local = runtime.registerScript(new LocalWoodcutting());
        LiveSubscriptions subs = subscriptions(() -> List.of(conn),
                entry(DeliveredWoodcutting.class.getName()), new DeliveredWoodcutting(), List.of());

        subs.start(CLIENT, SCRIPT_ID);

        assertNull(local.lastStartedAt(), "the local script the user did not pick must not start");
        SubscriptionState.Failed failed = assertInstanceOf(SubscriptionState.Failed.class, onlyRow(subs).state(),
                "the row must leave Installing so the picker stops waiting");
        assertTrue(failed.message().contains("A different script named " + SHARED_NAME),
                () -> "calm, specific message; was: " + failed.message());
    }

    @Test
    void start_deliveryWithNoNameClash_startsTheDeliveredScript() {
        DeliveredWoodcutting delivered = new DeliveredWoodcutting();
        LiveSubscriptions subs = subscriptions(() -> List.of(conn),
                entry(DeliveredWoodcutting.class.getName()), delivered, List.of());

        subs.start(CLIENT, SCRIPT_ID);

        ScriptRunner runner = runtime.findRunner(SHARED_NAME);
        assertNotNull(runner);
        assertSame(delivered, runner.getScript());
        assertNotNull(runner.lastStartedAt(), "the delivered script starts on the picked client");
        assertInstanceOf(SubscriptionState.Installed.class, onlyRow(subs).state());
    }

    // ── Fix 2: a blank scriptClass never adopts a same-named local script ──

    @Test
    void group_blankScriptClass_doesNotTreatASameNamedLocalScriptAsInstalled() {
        List<LocalScript> locals = List.of(new LocalScript(0, new LocalWoodcutting(), SHARED_NAME));
        runtime.registerScript(new LocalWoodcutting());
        LiveSubscriptions subs = subscriptions(() -> List.of(conn), entry(""), new DeliveredWoodcutting(), locals);

        SubscriptionEntry row = onlyRow(subs);

        assertInstanceOf(SubscriptionState.NotInstalled.class, row.state());
        assertTrue(row.localKey().isEmpty(), "the local row must stay visible under its own category");
    }

    // ── Fix 3: a throw after the install still settles the row ─────────────

    @Test
    void start_throwAfterInstall_failsTheRowInsteadOfLeavingItInstalling() {
        AtomicInteger calls = new AtomicInteger();
        int throwingCall = 2;
        Supplier<List<Connection>> flaky = () -> {
            if (calls.incrementAndGet() == throwingCall) {
                throw new IllegalStateException("connection list unavailable");
            }
            return List.of(conn);
        };
        LiveSubscriptions subs = subscriptions(flaky, entry(DeliveredWoodcutting.class.getName()),
                new DeliveredWoodcutting(), List.of());

        subs.start(CLIENT, SCRIPT_ID);

        SubscriptionState.Failed failed = assertInstanceOf(SubscriptionState.Failed.class, onlyRow(subs).state());
        assertTrue(failed.message().contains("connection list unavailable"));
    }
}
