package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorTab;
import com.botwithus.bot.cli.management.ManagementFile;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.core.runtime.LoadIssues;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live model over a stood-in host: a mocked context and connections whose
 * runtimes hand back mocked runners, a real ledger on disk, and an executor the
 * test drains by hand, so "off the render thread" is observable.
 */
class LiveInstalledModelTest {

    private static final Instant NOW = Instant.parse("2026-09-26T14:03:52Z");
    private static final Instant STARTED = NOW.minusSeconds(41 * 60 + 7);
    private static final Instant JAR_CHANGED = Instant.parse("2026-09-26T13:58:00Z");
    private static final Path WC_JAR = Path.of("scripts", "woodcutting-2.0.jar");
    private static final String WOODCUTTING = "Woodcutting";
    private static final int OLD_BUILD = 5;
    private static final int NEW_BUILD = 7;

    abstract static class Idle implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = WOODCUTTING, version = "2.0", category = ScriptCategory.WOODCUTTING,
            description = "Chops trees.")
    static final class Woodcutting extends Idle {
        @Override public List<ConfigField> getConfigFields() {
            return List.of(ConfigField.intField("logs", "Logs", 28));
        }
    }

    @ScriptManifest(name = "Divination", version = "1.0", category = ScriptCategory.DIVINATION)
    static final class Divination extends Idle { }

    @TempDir
    Path tmp;

    private final CliContext ctx = mock(CliContext.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final List<InspectorRequest> inspected = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private InstalledScriptsLedger ledger;
    private GroupStore groups;
    private ManagementTargets targets;
    private Optional<SdnCatalogueResult> catalogue = Optional.empty();
    private LiveInstalledModel model;

    @BeforeEach
    void setUp() {
        ledger = new InstalledScriptsLedger(tmp, clock);
        when(ctx.getConnections()).thenReturn(connections);
        when(ctx.getLastLoadReport()).thenReturn(LoadReport.EMPTY);
        when(ctx.getLoadIssues()).thenReturn(new LoadIssues(clock));
        when(ctx.afterReloadSetting()).thenReturn(AfterReload.REGISTER_ONLY);
        when(ctx.clientKeyOf(anyString())).thenAnswer(call -> new ClientKey.Pipe(call.getArgument(0)));
        groups = new GroupStore(new GroupsFile(tmp.resolve(GroupsFile.FILE_NAME)));
        targets = new ManagementTargets(new ManagementFile(tmp.resolve(ManagementFile.FILE_NAME)), groups);
        when(ctx.getManagementTargets()).thenReturn(targets);
        model = new LiveInstalledModel(new LiveInstalledModel.Deps(ctx, ledger, () -> catalogue, inspected::add,
                queued::add, dir -> { }, clock, tmp, "scripts/", Duration.ZERO));
    }

    private void drain() {
        List<Runnable> now = List.copyOf(queued);
        queued.clear();
        now.forEach(Runnable::run);
    }

    private void jarsInFolder(ScriptLoadResult... results) {
        when(ctx.getLastLoadReport()).thenReturn(new LoadReport(List.of(results)));
    }

    private static ScriptLoadResult jar(Path path, BotScript script, Instant changed) {
        return new ScriptLoadResult(path, Optional.of(script), Optional.empty(), List.of(), Optional.of(changed));
    }

    private static ScriptRunner runner(BotScript script, boolean isRunning, Liveness liveness, Instant startedAt) {
        ScriptRunner r = mock(ScriptRunner.class);
        when(r.getScript()).thenReturn(script);
        when(r.getScriptName()).thenReturn(script.getClass().getAnnotation(ScriptManifest.class).name());
        when(r.isRunning()).thenReturn(isRunning);
        when(r.liveness()).thenReturn(liveness);
        when(r.lastStartedAt()).thenReturn(startedAt);
        when(r.health()).thenReturn(ScriptHealth.HEALTHY);
        when(r.getProfiler()).thenReturn(new ScriptProfiler());
        return r;
    }

    private Connection client(String pipe, String account, boolean isAlive, ScriptRunner... runners) {
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of(runners));
        when(runtime.getQuarantined()).thenReturn(List.of());
        for (ScriptRunner r : runners) {
            when(runtime.findRunner(r.getScriptName())).thenReturn(r);
        }
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(pipe);
        when(conn.getAccountName()).thenReturn(account);
        when(conn.isAlive()).thenReturn(isAlive);
        when(conn.getRuntime()).thenReturn(runtime);
        connections.add(conn);
        return conn;
    }

    private static SdnCatalogueEntry entry(String id, String name, String version, String category,
                                           String scriptClass, Integer build) {
        return new SdnCatalogueEntry(id, name, "BotWithUs", "me", version, "2", "", "Harvests things.",
                scriptClass, false, true, true, true, category, null, build);
    }

    // ── Rows ───────────────────────────────────────────────────────────────

    @Test
    void aJarInTheFolder_isALocalRow_withADotForEachClientThatRanIt() {
        jarsInFolder(jar(WC_JAR, new Woodcutting(), JAR_CHANGED));
        client("BotWithUs_1", "Oakheart", true, runner(new Woodcutting(), true, Liveness.LIVE, STARTED));
        client("BotWithUs_2", "Wrenfield", true, runner(new Woodcutting(), false, Liveness.LIVE, null));
        client("BotWithUs_3", null, false, runner(new Woodcutting(), true, Liveness.LIVE, STARTED));

        InstalledScript row = model.view().find(WOODCUTTING).orElseThrow();

        assertEquals(ScriptSource.LOCAL, row.provenance().source());
        assertEquals(Optional.of("woodcutting-2.0.jar"), row.provenance().jarName());
        assertEquals(Optional.of("today 13:58"), row.provenance().changed());
        assertEquals("2.0", row.identity().version());
        assertEquals(1, row.identity().settingsCount());
        assertEquals(List.of(RunnerState.RUNNING, RunnerState.OFFLINE),
                row.runs().stream().map(ClientRun::state).toList());
        assertEquals(List.of("Oakheart", "BotWithUs_3"), row.runs().stream().map(ClientRun::clientName).toList());
        assertEquals("Running · 41:07", row.runs().getFirst().detail());
        assertEquals("Waiting · not connected", row.runs().get(1).detail());
        assertEquals(Set.of("BotWithUs_1", "BotWithUs_2", "BotWithUs_3"), row.registeredOn());
        assertEquals("1 of 2 running", row.summary().text());
    }

    @Test
    void aScriptManagementScriptsName_onSomeClient_listsThem_butNotOneThatManagesTheWholeHost() {
        String oakheart = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
        String wrenfield = "6b1e8c04d2f64a32c6e8a0b2d4f6b8c0";
        jarsInFolder(jar(WC_JAR, new Woodcutting(), JAR_CHANGED));
        client("BotWithUs_1", "Oakheart", true, runner(new Woodcutting(), true, Liveness.LIVE, STARTED));
        client("BotWithUs_2", "Wrenfield", true, runner(new Woodcutting(), true, Liveness.LIVE, STARTED));
        when(ctx.clientKeyOf("BotWithUs_1")).thenReturn(ClientKey.account(oakheart));
        when(ctx.clientKeyOf("BotWithUs_2")).thenReturn(ClientKey.account(wrenfield));
        GroupId woodcutters = groups.create("Woodcutters", Optional.empty()).orElseThrow().id();
        groups.addMember(woodcutters, ClientKey.account(wrenfield));
        targets.add("Break Scheduler", new Target.ClientScript(oakheart, WOODCUTTING));
        targets.add("World Balancer", new Target.Group(woodcutters));
        targets.add("Restart on Crash", Target.host());

        InstalledScript row = model.view().find(WOODCUTTING).orElseThrow();

        assertEquals(List.of("Break Scheduler", "World Balancer"), row.managedBy());
    }

    @Test
    void aStoreDeliveryAClientRuns_isAStoreRow_withTheStoresNewerBuild() throws IOException {
        ledger.record(Map.of(Divination.class.getName(),
                entry("div-1", "Divination", "1.1", "divination", Divination.class.getName(), OLD_BUILD)));
        catalogue = Optional.of(new SdnCatalogueResult.Delivered(List.of(
                entry("div-1", "Divination", "1.1", "divination", Divination.class.getName(), NEW_BUILD)), false));
        client("BotWithUs_1", "Fernmoss", true, runner(new Divination(), true, Liveness.STALLED, STARTED));

        InstalledScript row = model.view().find("Divination").orElseThrow();

        assertEquals(ScriptSource.STORE, row.provenance().source());
        assertTrue(row.provenance().jar().isEmpty());
        assertEquals("v1.1 in Store", row.provenance().update().orElseThrow().badge());
        assertEquals(RunnerState.STALLED, row.runs().getFirst().state());
        assertEquals("0 of 1 running · 1 stalled", row.summary().text());
    }

    @Test
    void aStoreInstallNothingHasLoadedSinceARestart_isStoreNotLoaded() throws IOException {
        String herblore = "com.example.herblore.Herblore";
        ledger.record(Map.of(herblore, entry("hb-1", "Herblore", "3.0", "herblore", herblore, OLD_BUILD)));
        catalogue = Optional.of(new SdnCatalogueResult.Delivered(List.of(
                entry("hb-1", "Herblore", "3.0", "herblore", herblore, OLD_BUILD)), false));

        InstalledScript row = model.view().find("store:" + herblore).orElseThrow();

        assertEquals("Herblore", row.name());
        assertEquals(ScriptCategory.HERBLORE, row.identity().category());
        assertTrue(row.provenance().isStoreNotLoaded());
        assertFalse(row.isStartable());
        assertTrue(row.runs().isEmpty());
    }

    @Test
    void aStoreInstallTheCatalogueNoLongerLists_isStillShown_byItsClassName() throws IOException {
        String herblore = "com.example.herblore.Herblore";
        ledger.record(Map.of(herblore, entry("hb-1", "Herblore", "3.0", "herblore", herblore, OLD_BUILD)));

        InstalledScript row = model.view().find("store:" + herblore).orElseThrow();

        assertEquals("Herblore", row.name());
        assertEquals(ScriptCategory.UNCATEGORIZED, row.identity().category());
    }

    @Test
    void aStoreInstallThatIsLoaded_isNotAlsoListedAsNotLoaded() throws IOException {
        ledger.record(Map.of(Divination.class.getName(),
                entry("div-1", "Divination", "1.0", "divination", Divination.class.getName(), OLD_BUILD)));
        client("BotWithUs_1", "Fernmoss", true, runner(new Divination(), false, Liveness.LIVE, null));

        assertEquals(List.of("Divination"), model.view().scripts().stream().map(InstalledScript::key).toList());
    }

    @Test
    void theFolderFailedLoads_areTheProblems() throws IOException {
        Files.createFile(tmp.resolve("broken.jar"));
        LoadIssues issues = new LoadIssues(clock);
        issues.record(ScriptFolder.SCRIPTS, List.of(ScriptLoadResult.failure(tmp.resolve("broken.jar"),
                new IllegalStateException("missing provides"), List.of())));
        when(ctx.getLoadIssues()).thenReturn(issues);

        InstalledView view = model.view();

        assertEquals(List.of("broken.jar"), view.problems().stream().map(LoadProblem::jarName).toList());
        assertEquals(1, view.attentionCount());
    }

    // ── Reload ─────────────────────────────────────────────────────────────

    @Test
    void reload_runsOffTheRenderThread_andStampsTheTimeItFinished() {
        assertEquals(Optional.empty(), model.view().header().reloadedAt());

        model.reload();
        model.reload();

        assertTrue(model.view().header().isReloading());
        verify(ctx, never()).reloadAllScripts(AfterReload.REGISTER_ONLY);
        assertEquals(1, queued.size(), "a second click while reloading queues nothing");

        drain();

        verify(ctx, times(1)).reloadAllScripts(AfterReload.REGISTER_ONLY);
        InstalledHeader header = model.view().header();
        assertFalse(header.isReloading());
        assertEquals(Optional.of("14:03:52"), header.reloadedAt());
        assertTrue(model.view().meta().endsWith(" · reloaded 14:03:52"), model.view().meta());
    }

    @Test
    void aReloadThatFails_isNotStamped_andCanBeTriedAgain() {
        when(ctx.reloadAllScripts(AfterReload.REGISTER_ONLY)).thenThrow(new IllegalStateException("boom"));

        model.reload();
        drain();

        assertEquals(Optional.empty(), model.view().header().reloadedAt());
        assertFalse(model.view().header().isReloading());
        model.reload();
        assertEquals(1, queued.size());
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Test
    void stopEverywhere_stopsEachRunningRunner_offTheRenderThread() {
        ScriptRunner running = runner(new Woodcutting(), true, Liveness.LIVE, STARTED);
        ScriptRunner stopped = runner(new Woodcutting(), false, Liveness.LIVE, STARTED);
        client("BotWithUs_1", "Oakheart", true, running);
        client("BotWithUs_2", "Wrenfield", true, stopped);

        model.stopEverywhere(WOODCUTTING);
        verify(running, never()).stop();

        drain();

        verify(running).stop();
        verify(stopped, never()).stop();
    }

    @Test
    void startOn_startsTheClientsOwnRunner_orAFreshCopy_andSkipsAClientThatIsNotConnected() {
        ScriptRunner idle = runner(new Woodcutting(), false, Liveness.LIVE, null);
        client("BotWithUs_1", "Oakheart", true, idle);
        Connection empty = client("BotWithUs_2", "Wrenfield", true);
        ScriptRunner offlineRunner = runner(new Woodcutting(), false, Liveness.LIVE, null);
        client("BotWithUs_3", null, false, offlineRunner);
        Woodcutting fresh = new Woodcutting();
        when(ctx.loadScripts()).thenReturn(List.of(new Divination(), fresh));

        model.startOn(WOODCUTTING, List.of("BotWithUs_1", "BotWithUs_2", "BotWithUs_3"));
        verify(idle, never()).start();
        drain();

        verify(idle).start();
        verify(empty.getRuntime()).startScript(fresh);
        verify(offlineRunner, never()).start();
    }

    @Test
    void openSettings_asksTheSharedInspectorForThatClientsScript() {
        client("BotWithUs_1", "Oakheart", true, runner(new Woodcutting(), true, Liveness.LIVE, STARTED));

        model.openSettings(WOODCUTTING, "BotWithUs_1");

        assertEquals(List.of(new InspectorRequest(new InspectorSubject.ClientScript("BotWithUs_1", WOODCUTTING),
                InspectorTab.SETTINGS)), inspected);
    }
}
