package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorTab;
import com.botwithus.bot.cli.gui.pages.installed.LoadProblem;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.core.runtime.ManagementLoadResult;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Management page's model over a real {@link CliContext}: real management
 * runners, targets file, group store, settings, audit log and failed-load
 * list. Only the clients are fakes, and every action runs on the calling
 * thread so its effect is visible at once.
 */
class LiveManagementModelTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";
    private static final String BREAKS = "Break Scheduler";
    private static final String RESTARTS = "Restart on Crash";
    private static final String WORLDS = "World Balancer";
    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String UUID_C = "00000000111111112222222233333333";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String WOODCUTTING = "Woodcutting";
    private static final int LOOP_MS = 5;
    private static final long AWAIT_SECONDS = 5;
    private static final long AWAIT_MS = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
    private static final long POLL_MS = 5;

    /** Counts its starts, and loops until stopped. */
    @ScriptManifest(name = BREAKS, version = "1.0", author = "You", description = "Staggers breaks.")
    public static final class BreakScheduler implements ManagementScript {
        final Semaphore starts = new Semaphore(0);
        @Override public void onStart(ManagementContext ctx) { starts.release(); }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
        @Override public List<ConfigField> getConfigFields() {
            return List.of(ConfigField.intField("breakEvery", "Break every (min)", 90));
        }
    }

    @ScriptManifest(name = RESTARTS, version = "1.0")
    public static final class RestartOnCrash implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    /** Crashes as it starts. */
    @ScriptManifest(name = WORLDS, version = "0.2")
    public static final class WorldBalancer implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) { throw new IllegalStateException("no worlds"); }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = WOODCUTTING, version = "1.0")
    public static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private String previousScriptsDir;
    private final List<InspectorRequest> inspected = new ArrayList<>();
    private final List<Path> opened = new ArrayList<>();
    private final List<ScriptRuntime> runtimes = new ArrayList<>();
    private CliContext ctx;
    private BreakScheduler breaks;
    private LiveManagementModel model;

    @BeforeEach
    void host() {
        previousScriptsDir = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, dir.resolve("scripts").toString());
        ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));
        ctx.initManagementRuntime();
        breaks = new BreakScheduler();
        ctx.getManagementRuntime().registerScript(breaks);
        ctx.getManagementRuntime().registerScript(new RestartOnCrash());
        ctx.getManagementRuntime().registerScript(new WorldBalancer());
        model = new LiveManagementModel(new LiveManagementModel.Deps(ctx, inspected::add, Runnable::run,
                opened::add, Clock.systemUTC(), dir.resolve("scripts").resolve("management"),
                "scripts/management/", Duration.ZERO));
    }

    @AfterEach
    void stopScripts() {
        ctx.getManagementRuntime().stopAll();
        runtimes.forEach(ScriptRuntime::stopAll);
        if (previousScriptsDir == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previousScriptsDir);
        }
    }

    private ManagementRow row(String name) {
        return model.view().find(name).orElseThrow();
    }

    private ManagementTargets targets() {
        return ctx.getManagementTargets();
    }

    private ManagementScriptRunner runner(String name) {
        return ctx.getManagementRuntime().findRunner(name);
    }

    private GroupId woodcutters() {
        GroupId id = TestContexts.createGroup(ctx, "Woodcutters");
        TestContexts.addMember(ctx, "Woodcutters", UUID_A);
        TestContexts.addMember(ctx, "Woodcutters", UUID_B);
        return id;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting: " + what);
            Thread.sleep(POLL_MS);
        }
    }

    // ── Rows ───────────────────────────────────────────────────────────────

    @Test
    void eachScriptsTargets_showAsTwoChipsThenPlusN_orNotApplied() {
        ScriptRuntime clientRuntime = TestContexts.runtime(PIPE_A);
        runtimes.add(clientRuntime);
        TestContexts.connectIdentified(ctx, PIPE_A, UUID_A, "Oakheart", clientRuntime);
        GroupId group = woodcutters();
        targets().add(BREAKS, new Target.Group(group));
        targets().add(BREAKS, new Target.ClientScript(UUID_A, WOODCUTTING));
        targets().add(BREAKS, new Target.ClientScript(UUID_B, "Divination"));
        targets().add(BREAKS, new Target.ClientScript(UUID_C, "Walk to Flag"));
        targets().add(RESTARTS, Target.host());

        AppliesTo breaksCell = row(BREAKS).appliesTo();
        ManagementRow restarts = row(RESTARTS);

        assertAll(
                () -> assertEquals(List.of("Woodcutters", "Oakheart · Woodcutting"),
                        breaksCell.shown().stream().map(TargetRow::label).toList()),
                () -> assertEquals("+2", breaksCell.more()),
                () -> assertEquals("fedcba98 · Divination, 00000000 · Walk to Flag", breaksCell.hiddenLabels()),
                () -> assertEquals("group · 2 clients", row(BREAKS).targets().getFirst().sub()),
                () -> assertEquals(List.of("Whole host"),
                        restarts.appliesTo().shown().stream().map(TargetRow::label).toList()),
                () -> assertEquals("every connected client", restarts.targets().getFirst().sub()),
                () -> assertTrue(row(WORLDS).appliesTo().isNotApplied()),
                () -> assertEquals(List.of(BREAKS, RESTARTS, WORLDS),
                        model.view().scripts().stream().map(ManagementRow::name).toList(), "by name"));
    }

    @Test
    void aTargetsOwnSettings_areCounted() {
        GroupId group = woodcutters();
        Target.Group target = new Target.Group(group);
        targets().add(BREAKS, target);
        ctx.getManagementSettings().apply(BREAKS, target,
                new ScriptConfig(Map.of("breakEvery", "120")),
                breaks.getConfigFields());

        assertEquals(1, row(BREAKS).targets().getFirst().ownSettings());
    }

    @Test
    void eachScriptsState_isRunningWithItsUptimeAndLoop_stopped_orCrashed() throws Exception {
        ctx.getManagementControl().start(BREAKS);
        ctx.getManagementControl().start(WORLDS);
        await(() -> runner(BREAKS).getProfiler().getLoopCount() > 0, "a loop");
        await(() -> !runner(WORLDS).isRunning() && runner(WORLDS).health().lastCrash().isPresent(), "the crash");

        ManagementView view = model.view();
        ManagementRow running = view.find(BREAKS).orElseThrow();
        ManagementRow crashed = view.find(WORLDS).orElseThrow();
        ManagementRow stopped = view.find(RESTARTS).orElseThrow();

        assertAll(
                () -> assertInstanceOf(RunState.Running.class, running.health().state()),
                () -> assertTrue(running.health().state().detail().matches("\\d+:\\d\\d"),
                        running.health().state().detail()),
                () -> assertTrue(running.health().avgLoopMs().isPresent()),
                () -> assertTrue(running.health().loopText().matches("\\d+"), running.health().loopText()),
                () -> assertEquals(new RunState.Crashed("IllegalStateException in onStart()"),
                        crashed.health().state()),
                () -> assertTrue(crashed.health().lastCrash().orElseThrow()
                        .startsWith("IllegalStateException in onStart() · "), crashed.health().lastCrash().get()),
                () -> assertEquals(1, crashed.health().crashes()),
                () -> assertEquals(new RunState.Stopped(), stopped.health().state()),
                () -> assertEquals("—", stopped.health().loopText()),
                () -> assertEquals("scripts/management/ · 3 loaded · 1 running", view.meta()),
                () -> assertEquals(1, view.attentionCount(), "the crashed script"));
    }

    @Test
    void aScriptsManifest_andWhatItLetsTheUserChange_areShown() {
        ScriptAbout about = row(BREAKS).about();

        assertAll(
                () -> assertEquals("1.0", about.version()),
                () -> assertEquals("You", about.author()),
                () -> assertEquals("Staggers breaks.", about.description()),
                () -> assertEquals("BreakScheduler", about.simpleClassName()),
                () -> assertEquals("1 field", about.settingsLine()));
    }

    // ── Targets ────────────────────────────────────────────────────────────

    @Test
    void theWholeHost_replacesEveryOtherTarget_andAnyOtherTargetReplacesTheWholeHost() {
        GroupId group = woodcutters();
        targets().add(BREAKS, new Target.Group(group));
        targets().add(BREAKS, new Target.ClientScript(UUID_A, WOODCUTTING));

        model.changeTargets(BREAKS, new TargetChange.Add(Target.host()), false);
        List<Target> onlyHost = targets().targetsOf(BREAKS);
        Optional<ManagerSlot> groupManager = ctx.getGroupStore().get(group).flatMap(ClientGroup::manager);
        model.changeTargets(BREAKS, new TargetChange.Add(new Target.ClientScript(UUID_B, WOODCUTTING)), false);

        assertAll(
                () -> assertEquals(List.of(Target.host()), onlyHost),
                () -> assertEquals(Optional.empty(), groupManager, "the group's manager slot is cleared"),
                () -> assertEquals(List.of(new Target.ClientScript(UUID_B, WOODCUTTING)), targets().targetsOf(BREAKS)));
    }

    @Test
    void aGroupTarget_isTheGroupsManagerSlot_soGivingItToAnotherScriptMovesIt() {
        GroupId group = woodcutters();
        model.changeTargets(BREAKS, new TargetChange.Add(new Target.Group(group)), false);
        Optional<ManagerSlot> first = ctx.getGroupStore().get(group).flatMap(ClientGroup::manager);

        model.changeTargets(RESTARTS, new TargetChange.Add(new Target.Group(group)), false);

        assertAll(
                () -> assertEquals(Optional.of(new ManagerSlot(BREAKS, true)), first),
                () -> assertEquals(Optional.of(new ManagerSlot(RESTARTS, true)),
                        ctx.getGroupStore().get(group).flatMap(ClientGroup::manager)),
                () -> assertEquals(List.of(), targets().targetsOf(BREAKS), "one manager per group"),
                () -> assertEquals(List.of(new Target.Group(group)), targets().targetsOf(RESTARTS)));
    }

    @Test
    void removingATarget_dropsItAndOnlyIt() {
        targets().add(BREAKS, new Target.ClientScript(UUID_A, WOODCUTTING));
        targets().add(BREAKS, new Target.ClientScript(UUID_B, WOODCUTTING));

        model.changeTargets(BREAKS, new TargetChange.Remove(new Target.ClientScript(UUID_A, WOODCUTTING)), false);

        assertEquals(List.of(new Target.ClientScript(UUID_B, WOODCUTTING)), targets().targetsOf(BREAKS));
    }

    @Test
    void aChangeToARunningScript_waitsForItsConfirm_andOnlyThenRestartsIt() throws Exception {
        GroupId group = woodcutters();
        ctx.getManagementControl().start(BREAKS);
        assertTrue(breaks.starts.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS), "not started");
        ManagementState state = new ManagementState(model, page -> { });
        Target.Group target = new Target.Group(group);

        state.requestChange(row(BREAKS), new TargetChange.Add(target), "Woodcutters");
        List<Target> beforeConfirm = targets().targetsOf(BREAKS);
        boolean isRestartedBeforeConfirm = breaks.starts.tryAcquire(LOOP_MS * 4L, TimeUnit.MILLISECONDS);
        boolean isAsking = state.pendingFor(BREAKS).isPresent();
        state.confirmChange();

        assertAll(
                () -> assertEquals(List.of(), beforeConfirm, "nothing changes before the confirm"),
                () -> assertFalse(isRestartedBeforeConfirm, "nothing restarts before the confirm"),
                () -> assertTrue(isAsking),
                () -> assertEquals(List.of(target), targets().targetsOf(BREAKS)),
                () -> assertTrue(breaks.starts.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS), "restarted"),
                () -> assertTrue(runner(BREAKS).isRunning()),
                () -> assertTrue(state.pendingFor(BREAKS).isEmpty()));
    }

    @Test
    void aCancelledChange_changesNothing_andAStoppedScriptChangesAtOnce() throws Exception {
        GroupId group = woodcutters();
        ctx.getManagementControl().start(BREAKS);
        assertTrue(breaks.starts.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS), "not started");
        ManagementState state = new ManagementState(model, page -> { });

        state.requestChange(row(BREAKS), new TargetChange.Add(new Target.Group(group)), "Woodcutters");
        state.cancelChange();
        state.requestChange(row(WORLDS), new TargetChange.Add(Target.host()), "Every connected client");

        assertAll(
                () -> assertEquals(List.of(), targets().targetsOf(BREAKS)),
                () -> assertEquals(List.of(Target.host()), targets().targetsOf(WORLDS), "not running: no confirm"),
                () -> assertFalse(runner(WORLDS).isRunning(), "a stopped script is not started by a change"),
                () -> assertFalse(breaks.starts.tryAcquire(LOOP_MS * 4L, TimeUnit.MILLISECONDS)));
    }

    // ── Activity and failures ──────────────────────────────────────────────

    @Test
    void activity_isTheAuditLog_newestFirst() {
        ctx.getOrchestratorAudit().record(BREAKS, "startScript", "Woodcutting on Oakheart", "done");
        ctx.getOrchestratorAudit().record(BREAKS, "stopScript", "Woodcutting on Duskwater",
                "refused: not in this script's targets");

        List<ActivityRow> activity = row(BREAKS).activity();
        String expectedTime = DateTimeFormatter.ofPattern("HH:mm").format(LocalTime.ofInstant(
                ctx.getOrchestratorAudit().entries(BREAKS).getLast().at(), ZoneOffset.UTC));

        assertAll(
                () -> assertEquals(List.of("stopScript", "startScript"),
                        activity.stream().map(ActivityRow::call).toList()),
                () -> assertEquals("stopScript · Woodcutting on Duskwater", activity.getFirst().what()),
                () -> assertEquals("refused: not in this script's targets", activity.getFirst().result()),
                () -> assertEquals(expectedTime, activity.getFirst().time()),
                () -> assertEquals(List.of(), row(RESTARTS).activity()));
    }

    @Test
    void aManagementJarThatFailedToLoad_isListed_andAScriptsJarIsNot() throws IOException {
        Path mule = Files.write(dir.resolve("mule-coordinator-0.3.jar"), new byte[] {1});
        Path woodcutting = Files.write(dir.resolve("woodcutting.jar"), new byte[] {1});
        ctx.getLoadIssues().record(ScriptFolder.MANAGEMENT, List.of(ManagementLoadResult.failure(mule,
                new ServiceConfigurationError("ManagementScript provider not found"))));
        ctx.getLoadIssues().record(ScriptFolder.SCRIPTS, List.of(ManagementLoadResult.failure(
                woodcutting, new IllegalStateException("broken"))));

        List<LoadProblem> problems = model.view().problems();

        assertAll(
                () -> assertEquals(1, problems.size()),
                () -> assertEquals("mule-coordinator-0.3.jar", problems.getFirst().jarName()),
                () -> assertEquals("ServiceConfigurationError: ManagementScript provider not found",
                        problems.getFirst().message()),
                () -> assertTrue(problems.getFirst().hasStackTrace()),
                () -> assertEquals(1, model.view().attentionCount()));
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Test
    void stopAll_stopsEveryRunningScript_andKeepsThemStopped() throws Exception {
        ctx.getManagementControl().start(BREAKS);
        ctx.getManagementControl().start(RESTARTS);
        assertTrue(breaks.starts.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS), "not started");

        model.stopAll();

        assertAll(
                () -> assertTrue(runner(BREAKS).awaitStop(AWAIT_MS)),
                () -> assertTrue(runner(RESTARTS).awaitStop(AWAIT_MS)),
                () -> assertFalse(targets().isDesiredRunning(BREAKS), "not started again after a host restart"),
                () -> assertFalse(targets().isDesiredRunning(RESTARTS)),
                () -> assertNotNull(runner(BREAKS), "still loaded, so it can be started again"));
    }

    @Test
    void settings_openTheSharedInspector_onTheDefaultsOrOneTargetsOwnValues() {
        Target.ClientScript target = new Target.ClientScript(UUID_A, WOODCUTTING);

        model.openSettings(BREAKS, Optional.empty());
        model.openSettings(BREAKS, Optional.of(target));
        model.openScriptUi(BREAKS);
        model.openSettings("Not Loaded", Optional.empty());

        assertEquals(List.of(
                new InspectorRequest(new InspectorSubject.ManagementScript(BREAKS), InspectorTab.SETTINGS),
                new InspectorRequest(new InspectorSubject.ManagementScript(BREAKS, Optional.of(target)),
                        InspectorTab.SETTINGS),
                InspectorRequest.scriptUi(new InspectorSubject.ManagementScript(BREAKS))),
                inspected);
    }

    @Test
    void openFolder_opensTheManagementFolder() {
        model.openFolder();

        assertEquals(List.of(dir.resolve("scripts").resolve("management")), opened);
    }

    @Test
    void theAddRow_offersEveryGroup_andEachScriptOnAConnectedClient() {
        ScriptRuntime clientRuntime = TestContexts.runtime(PIPE_A);
        clientRuntime.registerScript(new Woodcutter());
        runtimes.add(clientRuntime);
        TestContexts.connectIdentified(ctx, PIPE_A, UUID_A, "Oakheart", clientRuntime);
        woodcutters();

        TargetChoices choices = model.view().choices();

        assertAll(
                () -> assertEquals(List.of("Woodcutters · 2 clients"),
                        choices.groups().stream().map(TargetChoices.Option::label).toList()),
                () -> assertEquals(List.of(new TargetChoices.Option(new Target.ClientScript(UUID_A, WOODCUTTING),
                        "Oakheart · Woodcutting")), choices.clientScripts()));
    }

    @Test
    void untilTheFirstLoadPassHasRun_theViewSaysItIsLoading() {
        boolean isLoadingBefore = model.view().isReloading();

        ctx.reloadManagementScripts(AfterReload.REGISTER_ONLY);

        assertAll(
                () -> assertTrue(isLoadingBefore),
                () -> assertFalse(model.view().isReloading()));
    }
}
