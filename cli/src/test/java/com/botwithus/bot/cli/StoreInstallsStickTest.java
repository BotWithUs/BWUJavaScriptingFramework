package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.runtime.SdnDelivery;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.sdn.SdnPipeRoute;
import com.botwithus.bot.core.sdn.StoreRestorer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A Store install sticks: it is on a client after the host restarts, after a
 * reload of {@code scripts/}, and on a client that connects after the install, and
 * auto-start finds it after a restart.
 *
 * <p>Built the way the GUI host builds it: a real {@link CliContext} per host
 * process, a real {@link SdnInstaller}, ledger, shelf and {@link StoreRestorer}, and
 * real {@link ScriptRuntime}s. Only the launcher (a delivery source), the pipe (a
 * mocked {@link Connection}) and the {@code scripts/} folder (empty) are stood in for.
 * A "restart" is a second host over the same ledger directory and nothing else.
 */
class StoreInstallsStickTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String ENTRY_ID = "42";
    private static final String SCRIPT_NAME = "Store Woodcutter";
    private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
    private static final String DISPLAY_NAME = "Zezima";
    private static final int BUILD = 3;
    private static final int LOOP_MS = 20;
    private static final long START_TIMEOUT_SECONDS = 10;
    private static final double MID_JITTER = 0.5;

    @TempDir
    Path tmp;

    private final List<ScriptRuntime> runtimes = new ArrayList<>();
    private final List<HostSettings> settings = new ArrayList<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int hosts;
    /** The restorers' clock; a test moves it to let a wait pass. */
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    /** The delivered script. Counts its own starts, which is its per-run state. */
    @ScriptManifest(name = SCRIPT_NAME)
    public static final class StoreWoodcutter implements BotScript {
        private final AtomicInteger starts = new AtomicInteger();

        @Override public void onStart(ScriptContext context) {
            starts.incrementAndGet();
        }

        @Override public int onLoop() {
            return LOOP_MS;
        }

        @Override public void onStop() { }

        int starts() {
            return starts.get();
        }
    }

    /** What one host process is made of. */
    private record Host(CliContext ctx, SdnInstaller installer, StoreRestorer restorer,
                        List<Runnable> queuedRestores) {

        void runQueuedRestores() {
            List<Runnable> tasks = List.copyOf(queuedRestores);
            queuedRestores.clear();
            tasks.forEach(Runnable::run);
        }
    }

    @AfterEach
    void tearDown() {
        runtimes.forEach(ScriptRuntime::stopAll);
        settings.forEach(HostSettings::close);
    }

    private static SdnCatalogueEntry entry() {
        return new SdnCatalogueEntry(ENTRY_ID, SCRIPT_NAME, "author", "me", "1.0", "2", "", "",
                StoreWoodcutter.class.getName(), false, true, true, false, null, null, BUILD);
    }

    /** The launcher: one jar holding the script, with its provider to make more. */
    private static SdnDelivery oneJar() {
        return new SdnDelivery(1, List.of(new SdnDelivery.Jar(0, List.of(new StoreWoodcutter()),
                List.of(StoreWoodcutter::new))));
    }

    private Host host(Supplier<SdnDelivery> launcher, boolean defersRestores) {
        hosts++;
        Path dir = tmp.resolve("host" + hosts);
        PrintStream ps = new PrintStream(output, true, StandardCharsets.UTF_8);
        LogBuffer logBuffer = new LogBuffer();
        CliContext ctx = spy(new CliContext(logBuffer, new LogCapture(logBuffer, ps, ps), dir.resolve("groups.json")));
        HostSettings hostSettings = HostSettings.open(dir.resolve("settings"));
        settings.add(hostSettings);
        ctx.setSettings(hostSettings);
        // The scripts folder is empty: only the Store can supply this script.
        doReturn(List.of()).when(ctx).loadScripts();
        doReturn(List.of()).when(ctx).loadBlueprints();
        InstalledScriptsLedger ledger = new InstalledScriptsLedger(tmp.resolve("home"), InstantSource.fixed(NOW));
        SdnInstaller installer = new SdnInstaller(tmp.resolve("rendezvous"), () -> true, launcher,
                SdnPipeRoute.none(), ledger, ctx.storeShelf());
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(entry()), false), Runnable::run,
                InstantSource.fixed(NOW), () -> MID_JITTER);
        catalogue.requestNow();
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        Executor restores = defersRestores ? queued::add : Runnable::run;
        StoreRestorer restorer = new StoreRestorer(new StoreRestorer.Deps(ledger, installer.shelf(),
                catalogue::shown, installer::isDeliveryEnabled, installer::install,
                ctx::registerStoreScriptsOnLiveClients, restores, now::get));
        ctx.setStoreRestorer(restorer);
        return new Host(ctx, installer, restorer, queued);
    }

    private Host host() {
        return host(StoreInstallsStickTest::oneJar, false);
    }

    private Connection client(Host host, String name) {
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        runtimes.add(runtime);
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        when(conn.getIdentifiedUuid()).thenReturn(Optional.of(ACCOUNT));
        when(conn.isAlive()).thenReturn(true);
        when(conn.getRuntime()).thenReturn(runtime);
        assertTrue(host.ctx().registerConnection(conn));
        return conn;
    }

    /** The click: the installer asks the launcher, then the delivery goes to every live client. */
    private static void install(Host host) {
        SdnInstallResult result = host.installer().install(List.of(entry()));
        SdnInstallResult.Installed installed = assertInstanceOf(SdnInstallResult.Installed.class, result);
        host.ctx().registerStoreScriptsOnLiveClients(installed.scripts());
    }

    private static ScriptRunner storeRunner(Connection conn) {
        ScriptRunner runner = conn.getRuntime().findRunner(SCRIPT_NAME);
        assertNotNull(runner, "the Store script is not registered on " + conn.getName());
        return runner;
    }

    private static StoreWoodcutter script(Connection conn) {
        return assertInstanceOf(StoreWoodcutter.class, storeRunner(conn).getScript());
    }

    private String output() {
        return output.toString(StandardCharsets.UTF_8);
    }

    /**
     * Restart. The restore runs before any client connects, as the host's start-up
     * timer runs it, so it does not depend on a client connecting to happen.
     * Fails at 5e5a8e1: nothing reads the ledger back, so the new host has nothing.
     */
    @Test
    void installThenRestart_theScriptIsOnTheNewHostsClients() {
        install(host());

        Host restarted = host();
        restarted.restorer().request();
        Connection conn = client(restarted, "BotWithUs_1");

        assertEquals(SCRIPT_NAME, storeRunner(conn).getScriptName());
        assertTrue(restarted.restorer().pending().isEmpty());
    }

    /** Restart, with the client already connected when the restore lands. */
    @Test
    void installThenRestart_aClientConnectedBeforeTheRestoreGetsItToo() {
        install(host());

        Host restarted = host(StoreInstallsStickTest::oneJar, true);
        Connection conn = client(restarted, "BotWithUs_1");
        assertEquals(1, restarted.queuedRestores().size(), "the connect nudged the restorer");
        restarted.runQueuedRestores();

        storeRunner(conn);
    }

    /** Fails at 5e5a8e1: a reload registers only what {@code scripts/} holds. */
    @Test
    void installThenReload_theScriptIsStillRegistered() {
        Host host = host();
        Connection conn = client(host, "BotWithUs_1");
        install(host);
        storeRunner(conn);

        host.ctx().reloadScripts(List.of(conn), AfterReload.REGISTER_ONLY);

        storeRunner(conn);
    }

    /** Fails at 5e5a8e1: a delivery is registered only on the clients live at the click. */
    @Test
    void installThenConnectANewClient_theScriptIsAvailableThere() {
        Host host = host();
        install(host);

        Connection later = client(host, "BotWithUs_2");

        storeRunner(later);
    }

    /**
     * Fails at 5e5a8e1: the Store registered its one delivered object on every
     * client, so a start on one client counted on the other's script too.
     */
    @Test
    @Timeout(START_TIMEOUT_SECONDS)
    void twoClients_getTheirOwnInstance_andPerRunStateDoesNotLeak() throws InterruptedException {
        Host host = host();
        Connection bot1 = client(host, "BotWithUs_1");
        Connection bot2 = client(host, "BotWithUs_2");
        install(host);
        assertNotSame(script(bot1), script(bot2));

        storeRunner(bot1).start();
        while (script(bot1).starts() == 0) {
            TimeUnit.MILLISECONDS.sleep(LOOP_MS);
        }

        assertEquals(1, script(bot1).starts());
        assertEquals(0, script(bot2).starts(), "bot2's copy never started");
    }

    /**
     * Auto-start after a restart: the profile names the Store script and the restore
     * has not landed yet. Fails at 5e5a8e1 with "Script not found".
     */
    @Test
    @Timeout(START_TIMEOUT_SECONDS)
    void autoStartAfterRestart_waitsForTheRestoreThenStartsTheScript() {
        install(host());
        Host restarted = host(StoreInstallsStickTest::oneJar, true);
        AutoStartManager autoStart = autoStartFor(restarted);
        Connection conn = client(restarted, "BotWithUs_1");

        autoStart.onConnectionEstablished(conn, DISPLAY_NAME);
        assertTrue(output().contains("Waiting for the launcher"), output());
        restarted.runQueuedRestores();

        assertTrue(storeRunner(conn).isRunning());
        assertFalse(output().contains("Script not found"), output());
    }

    /** When the restore fails, auto-start says why at once rather than waiting forever. */
    @Test
    void autoStartAfterRestart_whenTheRestoreFailed_saysWhyRatherThanWaiting() {
        install(host());
        Host restarted = host(SdnDelivery::none, false);
        restarted.restorer().request();
        AutoStartManager autoStart = autoStartFor(restarted);
        Connection conn = client(restarted, "BotWithUs_1");

        autoStart.onConnectionEstablished(conn, DISPLAY_NAME);

        assertTrue(output().contains("Script not found: " + SCRIPT_NAME + " (Store scripts could not be delivered "
                + "again: " + SCRIPT_NAME + ": the launcher did not deliver it). It will start if the launcher "
                + "delivers it later."), output());
        assertFalse(output().contains("Waiting for the launcher"), output());
    }

    /**
     * S1, end to end: after a restart the first restore fails for want of a game
     * client, then a client connects, which retries at once, and the script starts.
     * Fails if the connect waits out the backoff, or if auto-start gives up after
     * the first failure.
     */
    @Test
    @Timeout(START_TIMEOUT_SECONDS)
    void autoStartAfterRestart_aFailedRestoreThenAConnect_startsTheScript() {
        install(host());
        AtomicInteger launcherCalls = new AtomicInteger();
        Host restarted = host(() -> launcherCalls.getAndIncrement() == 0 ? SdnDelivery.none() : oneJar(), true);
        AutoStartManager autoStart = autoStartFor(restarted);
        restarted.restorer().request();
        restarted.runQueuedRestores();
        assertTrue(restarted.restorer().lastFailure().isPresent(), "the first restore failed");
        now.set(NOW.plus(StoreRestorer.RETRY_AFTER));

        Connection conn = client(restarted, "BotWithUs_1");
        autoStart.onConnectionEstablished(conn, DISPLAY_NAME);
        restarted.runQueuedRestores();

        assertTrue(storeRunner(conn).isRunning());
    }

    private AutoStartManager autoStartFor(Host host) {
        ScriptProfileStore profiles = new ScriptProfileStore(tmp.resolve("profiles" + hosts));
        profiles.setAutoStart(ACCOUNT, true);
        profiles.setAccountScripts(ACCOUNT, List.of(SCRIPT_NAME));
        return new AutoStartManager(host.ctx(), profiles, settings.getLast());
    }
}
