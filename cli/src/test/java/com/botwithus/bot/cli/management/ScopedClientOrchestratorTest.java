package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ClientOrchestrator.OpResult;
import com.botwithus.bot.api.script.ClientOrchestrator.ScheduleOpResult;
import com.botwithus.bot.api.script.ClientOrchestrator.ScheduledScriptEntry;
import com.botwithus.bot.api.script.ClientOrchestrator.ScriptStatusEntry;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.api.script.ManagementTarget;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ManagementAction;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.management.OrchestratorAuditLog.Entry;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.botwithus.bot.cli.TestContexts.connectIdentified;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A management script's orchestrator, as the host builds it for the script,
 * over the real {@link CliContext} and {@link com.botwithus.bot.cli.ClientManager}.
 * Three identified clients run real (trivial) scripts; only their agents are fakes.
 *
 * <p>Client A is in group Woodcutters and client C in group Others. The matrix
 * gives the script one kind of target at a time: the whole host, the
 * Woodcutters group, Fishing on client B, or nothing.</p>
 */
class ScopedClientOrchestratorTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String UUID_C = "00000000000000000000000000000abc";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_B = "BotWithUs_2002";
    private static final String PIPE_C = "BotWithUs_3003";
    private static final String WOODCUTTING = "Woodcutting";
    private static final String FISHING = "Fishing";
    private static final String MANAGER = "Manager";
    private static final String OTHER_MANAGER = "Other Manager";
    private static final String WOODCUTTERS = "Woodcutters";
    private static final String OTHERS = "Others";
    private static final int LOOP_MS = 50;
    private static final long AWAIT_SECONDS = 5;
    private static final Duration LATER = Duration.ofHours(1);
    private static final List<String> ALL_PIPES = List.of(PIPE_A, PIPE_B, PIPE_C);

    /** One kind of target at a time. */
    enum Aim { WHOLE_HOST, GROUP, CLIENT_SCRIPT, NOT_APPLIED }

    @ScriptManifest(name = WOODCUTTING, version = "1.0", author = "test")
    public static final class Woodcutting implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = FISHING, version = "1.0", author = "test")
    public static final class Fishing implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    /** Hands the context it was started with to the test, then ends its run. */
    @ScriptManifest(name = MANAGER, version = "1.0", author = "test")
    public static final class Manager extends ContextCatcher { }

    @ScriptManifest(name = OTHER_MANAGER, version = "1.0", author = "test")
    public static final class OtherManager extends ContextCatcher { }

    abstract static class ContextCatcher implements ManagementScript {
        final CompletableFuture<ManagementContext> context = new CompletableFuture<>();
        @Override public void onStart(ManagementContext ctx) { context.complete(ctx); }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
    private final Map<String, ScriptRuntime> runtimes = new LinkedHashMap<>();
    private CliContext ctx;
    private GroupId woodcutters;

    @BeforeEach
    void connectThreeClients() {
        ctx = TestContexts.inDirInLine(dir, discard);
        connect(PIPE_A, UUID_A, "Alpha");
        connect(PIPE_B, UUID_B, "Bravo");
        connect(PIPE_C, UUID_C, "Charlie");
        woodcutters = TestContexts.createGroup(ctx, WOODCUTTERS);
        TestContexts.addMember(ctx, WOODCUTTERS, UUID_A);
        TestContexts.createGroup(ctx, OTHERS);
        TestContexts.addMember(ctx, OTHERS, UUID_C);
    }

    @AfterEach
    void stopEverything() {
        ctx.getClientManager().cancelAllSchedules();
        runtimes.values().forEach(ScriptRuntime::stopAll);
        if (ctx.getManagementRuntime() != null) {
            ctx.getManagementRuntime().stopAll();
        }
    }

    private void connect(String pipe, String uuid, String name) {
        ScriptRuntime runtime = TestContexts.runtime(pipe);
        runtimes.put(pipe, runtime);
        installScripts(pipe);
        connectIdentified(ctx, pipe, uuid, name, runtime);
    }

    /** Installs both scripts, not running; stopping everything on a client uninstalls them. */
    private void installScripts(String pipe) {
        runtimes.get(pipe).registerScript(new Woodcutting());
        runtimes.get(pipe).registerScript(new Fishing());
    }

    /** Gives the manager the one target {@code aim} names, and returns the orchestrator the host gives it. */
    private ClientOrchestrator scopedTo(Aim aim) {
        ManagementTargets targets = ctx.getManagementTargets();
        switch (aim) {
            case WHOLE_HOST -> targets.add(MANAGER, Target.host());
            case GROUP -> targets.add(MANAGER, new Target.Group(woodcutters));
            case CLIENT_SCRIPT -> targets.add(MANAGER, new Target.ClientScript(UUID_B, FISHING));
            case NOT_APPLIED -> { }
        }
        return contextOf(new Manager()).getOrchestrator();
    }

    /** Starts {@code script} as the host would, and returns the context it was given. */
    private ManagementContext contextOf(ContextCatcher script) {
        ctx.initManagementRuntime();
        ctx.getManagementRuntime().registerScript(script).start();
        try {
            return script.context.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the management script never started", e);
        }
    }

    private boolean isRunning(String pipe, String script) {
        ScriptRunner runner = runtimes.get(pipe).findRunner(script);
        return runner != null && runner.isRunning();
    }

    private Set<String> pipesRunning(String script) {
        return ALL_PIPES.stream().filter(pipe -> isRunning(pipe, script)).collect(Collectors.toSet());
    }

    private Set<String> pipesScheduled() {
        return ctx.getClientManager().listScheduled().stream()
                .map(ScheduledScriptEntry::clientName)
                .collect(Collectors.toSet());
    }

    private Set<String> pipesScheduled(String script) {
        return ctx.getClientManager().listScheduled().stream()
                .filter(entry -> entry.scriptName().equals(script))
                .map(ScheduledScriptEntry::clientName)
                .collect(Collectors.toSet());
    }

    private static Set<String> statusPairs(List<ScriptStatusEntry> entries) {
        return entries.stream().map(e -> e.clientName() + "/" + e.scriptName()).collect(Collectors.toSet());
    }

    private static Set<String> pairs(String... pipesAndScripts) {
        return Set.of(pipesAndScripts);
    }

    /** Every client, both scripts. */
    private static final Set<String> EVERYTHING = pairs(
            PIPE_A + "/" + WOODCUTTING, PIPE_A + "/" + FISHING,
            PIPE_B + "/" + WOODCUTTING, PIPE_B + "/" + FISHING,
            PIPE_C + "/" + WOODCUTTING, PIPE_C + "/" + FISHING);

    @Nested
    class Matrix {

        @ParameterizedTest
        @EnumSource(Aim.class)
        void clientQueries_listOnlyCoveredClients(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);

            List<String> expected = switch (aim) {
                case WHOLE_HOST -> ALL_PIPES;
                case GROUP -> List.of(PIPE_A);
                case CLIENT_SCRIPT -> List.of(PIPE_B);
                case NOT_APPLIED -> List.of();
            };
            assertAll(
                    () -> assertEquals(expected, scoped.getClientNames()),
                    () -> assertEquals(aim == Aim.WHOLE_HOST, scoped.isClientAlive(PIPE_C)));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void groupQueries_showOnlyTargetGroups(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);

            Set<String> expectedGroups = switch (aim) {
                case WHOLE_HOST -> Set.of(WOODCUTTERS, OTHERS);
                case GROUP -> Set.of(WOODCUTTERS);
                case CLIENT_SCRIPT, NOT_APPLIED -> Set.of();
            };
            boolean seesWoodcutters = expectedGroups.contains(WOODCUTTERS);
            assertAll(
                    () -> assertEquals(expectedGroups, scoped.getGroupNames()),
                    () -> assertEquals(seesWoodcutters ? Set.of(PIPE_A) : Set.of(),
                            scoped.getGroupMembers(WOODCUTTERS)),
                    () -> assertEquals(seesWoodcutters ? 2 : 0, scoped.getStatusForGroup(WOODCUTTERS).size()),
                    () -> assertEquals(aim == Aim.WHOLE_HOST ? 2 : 0, scoped.getStatusForGroup(OTHERS).size()));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void groupChanges_needTheWholeHost(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);
            boolean isWholeHost = aim == Aim.WHOLE_HOST;

            boolean isCreated = scoped.createGroup("New", "made by a manager");
            boolean isAdded = scoped.addToGroup(WOODCUTTERS, PIPE_B);
            boolean isDeleted = scoped.deleteGroup(OTHERS);

            assertAll(
                    () -> assertEquals(isWholeHost, isCreated),
                    () -> assertEquals(isWholeHost, ctx.findGroup("New").isPresent()),
                    () -> assertEquals(isWholeHost, isAdded),
                    () -> assertEquals(isWholeHost, ctx.findGroup(WOODCUTTERS).orElseThrow().contains(UUID_B)),
                    () -> assertEquals(isWholeHost, isDeleted),
                    () -> assertEquals(!isWholeHost, ctx.findGroup(OTHERS).isPresent()));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void aSingleClientStart_happensOnlyWhenTheClientScriptIsCovered(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);
            boolean isFishingOnBCovered = aim == Aim.WHOLE_HOST || aim == Aim.CLIENT_SCRIPT;

            OpResult fishing = scoped.startScript(PIPE_B, FISHING);
            OpResult woodcutting = scoped.startScript(PIPE_B, WOODCUTTING);

            assertAll(
                    () -> assertEquals(isFishingOnBCovered, fishing.success(), fishing.message()),
                    () -> assertEquals(isFishingOnBCovered, isRunning(PIPE_B, FISHING)),
                    () -> assertEquals(aim == Aim.WHOLE_HOST, woodcutting.success(), woodcutting.message()),
                    () -> assertEquals(aim == Aim.WHOLE_HOST, isRunning(PIPE_B, WOODCUTTING)),
                    () -> assertEquals(aim == Aim.WHOLE_HOST ? "started" : ScopedClientOrchestrator.NOT_IN_TARGETS,
                            woodcutting.message()));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void aGroupStart_happensOnlyOnATargetGroup(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);
            boolean seesGroup = aim == Aim.WHOLE_HOST || aim == Aim.GROUP;

            List<OpResult> results = scoped.startScriptOnGroup(WOODCUTTERS, WOODCUTTING);

            assertEquals(seesGroup ? Set.of(PIPE_A) : Set.of(), pipesRunning(WOODCUTTING));
            if (!seesGroup) {
                assertEquals(List.of(new OpResult(false, WOODCUTTERS, WOODCUTTING,
                        ScopedClientOrchestrator.NOT_IN_TARGETS)), results);
            }
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void aStartOnEveryClient_reachesOnlyCoveredClients(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);

            scoped.startScriptOnAll(FISHING);

            Set<String> expected = switch (aim) {
                case WHOLE_HOST -> Set.copyOf(ALL_PIPES);
                case GROUP -> Set.of(PIPE_A);
                case CLIENT_SCRIPT -> Set.of(PIPE_B);
                case NOT_APPLIED -> Set.of();
            };
            assertEquals(expected, pipesRunning(FISHING));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void stoppingEverything_stopsOnlyWhatIsCovered(Aim aim) {
            ctx.getClientManager().startScriptOnAll(WOODCUTTING);
            ctx.getClientManager().startScriptOnAll(FISHING);
            ClientOrchestrator scoped = scopedTo(aim);

            scoped.stopAllScriptsOnAll();

            Set<String> stillRunning = new HashSet<>();
            ALL_PIPES.forEach(pipe -> Stream.of(WOODCUTTING, FISHING)
                    .filter(script -> isRunning(pipe, script))
                    .forEach(script -> stillRunning.add(pipe + "/" + script)));
            Set<String> expected = switch (aim) {
                case WHOLE_HOST -> Set.of();
                case GROUP -> pairs(PIPE_B + "/" + WOODCUTTING, PIPE_B + "/" + FISHING,
                        PIPE_C + "/" + WOODCUTTING, PIPE_C + "/" + FISHING);
                case CLIENT_SCRIPT -> without(EVERYTHING, PIPE_B + "/" + FISHING);
                case NOT_APPLIED -> EVERYTHING;
            };
            assertEquals(expected, stillRunning);
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void schedulingOnEveryClient_reachesOnlyCoveredClients(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);

            scoped.scheduleScriptOnAll(FISHING, LATER);
            ScheduleOpResult onC = scoped.scheduleScript(PIPE_C, WOODCUTTING, LATER);

            Set<String> expected = switch (aim) {
                case WHOLE_HOST -> Set.copyOf(ALL_PIPES);
                case GROUP -> Set.of(PIPE_A);
                case CLIENT_SCRIPT -> Set.of(PIPE_B);
                case NOT_APPLIED -> Set.of();
            };
            assertAll(
                    () -> assertEquals(aim == Aim.WHOLE_HOST, onC.success(), onC.message()),
                    () -> assertEquals(expected, pipesScheduled(FISHING)));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void scheduleQueries_andCancelling_coverOnlyCoveredSchedules(Aim aim) {
            ctx.getClientManager().scheduleScriptOnAll(FISHING, LATER);
            ClientOrchestrator scoped = scopedTo(aim);
            Set<String> covered = switch (aim) {
                case WHOLE_HOST -> Set.copyOf(ALL_PIPES);
                case GROUP -> Set.of(PIPE_A);
                case CLIENT_SCRIPT -> Set.of(PIPE_B);
                case NOT_APPLIED -> Set.of();
            };
            Set<String> listed = scoped.listScheduled().stream()
                    .map(ScheduledScriptEntry::clientName)
                    .collect(Collectors.toSet());
            String idOnC = ctx.getClientManager().listScheduledForClient(PIPE_C).getFirst().scheduleId();

            boolean isCancelledOnC = scoped.cancelSchedule(PIPE_C, idOnC);
            scoped.cancelAllSchedules();

            Set<String> uncovered = new HashSet<>(ALL_PIPES);
            uncovered.removeAll(covered);
            assertAll(
                    () -> assertEquals(covered, listed),
                    () -> assertEquals(aim == Aim.WHOLE_HOST, isCancelledOnC),
                    () -> assertEquals(uncovered, pipesScheduled()));
        }

        @ParameterizedTest
        @EnumSource(Aim.class)
        void status_listsOnlyCoveredClientScripts(Aim aim) {
            ClientOrchestrator scoped = scopedTo(aim);

            Set<String> expected = switch (aim) {
                case WHOLE_HOST -> EVERYTHING;
                case GROUP -> pairs(PIPE_A + "/" + WOODCUTTING, PIPE_A + "/" + FISHING);
                case CLIENT_SCRIPT -> pairs(PIPE_B + "/" + FISHING);
                case NOT_APPLIED -> Set.of();
            };
            assertEquals(expected, statusPairs(scoped.getStatusAll()));
        }

        private static Set<String> without(Set<String> from, String pair) {
            return from.stream().filter(p -> !p.equals(pair)).collect(Collectors.toSet());
        }
    }

    @Nested
    class Targets {

        @Test
        void aScriptThatIsNotApplied_stillRuns_butSeesAndDoesNothing() {
            ClientOrchestrator scoped = scopedTo(Aim.NOT_APPLIED);

            List<OpResult> started = scoped.startScriptOnAll(WOODCUTTING);
            scoped.startScriptOnGroup(WOODCUTTERS, WOODCUTTING);

            assertAll(
                    () -> assertEquals(List.of(), scoped.getClientNames()),
                    () -> assertEquals(Set.of(), scoped.getGroupNames()),
                    () -> assertEquals(List.of(), started),
                    () -> assertEquals(Set.of(), pipesRunning(WOODCUTTING)));
        }

        @Test
        void eachScript_getsAnOrchestratorForItsOwnTargets() {
            ctx.getManagementTargets().add(OTHER_MANAGER, new Target.ClientScript(UUID_C, WOODCUTTING));
            ClientOrchestrator manager = scopedTo(Aim.GROUP);
            ClientOrchestrator other = contextOf(new OtherManager()).getOrchestrator();

            assertAll(
                    () -> assertEquals(List.of(PIPE_A), manager.getClientNames()),
                    () -> assertEquals(List.of(PIPE_C), other.getClientNames()));
        }

        @Test
        void aChangeOfTargets_appliesToTheNextCall_withoutARestart() {
            ClientOrchestrator scoped = scopedTo(Aim.GROUP);

            ctx.getManagementTargets().add(MANAGER, Target.host());

            assertEquals(ALL_PIPES, scoped.getClientNames());
        }

        @Test
        void theScriptReadsItsTargets_withTheGroupsNameAsItIsNow() {
            ctx.getManagementTargets().add(MANAGER, new Target.Group(woodcutters));
            ManagementContext context = contextOf(new Manager());

            ctx.getGroupStore().rename(woodcutters, "Lumberjacks");

            assertAll(
                    () -> assertEquals(Set.of(new ManagementTarget.Group(woodcutters.toString(), "Lumberjacks")),
                            context.targets()),
                    () -> assertEquals(Set.of("Lumberjacks"), context.getOrchestrator().getGroupNames()),
                    () -> assertEquals(List.of(PIPE_A), context.getOrchestrator().getClientNames()));
        }

        @Test
        void aWholeHostScript_pausedOnAGroup_startsNothingOnThatGroup() {
            ClientOrchestrator scoped = scopedTo(Aim.WHOLE_HOST);
            ctx.getGroupStore().setManager(woodcutters, Optional.of(new ManagerSlot(MANAGER, false)));

            scoped.startScriptOnAll(WOODCUTTING);

            assertEquals(Set.of(PIPE_B, PIPE_C), pipesRunning(WOODCUTTING));
        }
    }

    @Nested
    class PausedManager {

        @Test
        void afterStopAllOnItsGroup_theManagerCannotStartAnythingThere() {
            ClientOrchestrator scoped = scopedTo(Aim.GROUP);
            ctx.getClientManager().startScript(PIPE_A, WOODCUTTING);

            ctx.getManagementControl().stopAllOnGroup(woodcutters);
            installScripts(PIPE_A);
            OpResult start = scoped.startScript(PIPE_A, WOODCUTTING);
            OpResult restart = scoped.restartScript(PIPE_A, WOODCUTTING);
            List<OpResult> onGroup = scoped.startScriptOnGroup(WOODCUTTERS, FISHING);
            scoped.startScriptOnAll(WOODCUTTING);
            ScheduleOpResult scheduled = scoped.scheduleScript(PIPE_A, WOODCUTTING, LATER);

            assertAll(
                    () -> assertEquals(ScopedClientOrchestrator.PAUSED, start.message()),
                    () -> assertEquals(ScopedClientOrchestrator.PAUSED, restart.message()),
                    () -> assertEquals(List.of(new OpResult(false, PIPE_A, FISHING, ScopedClientOrchestrator.PAUSED)),
                            onGroup),
                    () -> assertEquals(ScopedClientOrchestrator.PAUSED, scheduled.message()),
                    () -> assertEquals(Set.of(), pipesRunning(WOODCUTTING)),
                    () -> assertEquals(Set.of(), pipesRunning(FISHING)),
                    () -> assertEquals(Set.of(), pipesScheduled()),
                    () -> assertEquals(List.of(PIPE_A), scoped.getClientNames(), "it still sees its group"));
        }

        @Test
        void aPausedManager_mayStillStopScriptsOnItsGroup() {
            ClientOrchestrator scoped = scopedTo(Aim.GROUP);
            ctx.getManagementTargets().setManagerPaused(woodcutters, true);
            ctx.getClientManager().startScript(PIPE_A, WOODCUTTING);

            OpResult stop = scoped.stopScript(PIPE_A, WOODCUTTING);

            assertAll(
                    () -> assertTrue(stop.success(), stop.message()),
                    () -> assertFalse(isRunning(PIPE_A, WOODCUTTING)));
        }

        @Test
        void resumingTheManager_letsItStartAgain() {
            ClientOrchestrator scoped = scopedTo(Aim.GROUP);
            ctx.getManagementControl().stopAllOnGroup(woodcutters);
            installScripts(PIPE_A);

            assertTrue(ctx.getManagementControl().resumeManager(woodcutters));
            OpResult start = scoped.startScript(PIPE_A, WOODCUTTING);

            assertAll(
                    () -> assertTrue(start.success(), start.message()),
                    () -> assertTrue(isRunning(PIPE_A, WOODCUTTING)));
        }
    }

    @Nested
    class Audit {

        private final List<HostEvent> published = new CopyOnWriteArrayList<>();

        @BeforeEach
        void listen() {
            ctx.getHostEvents().subscribe(published::add);
        }

        private List<ManagementAction> actions() {
            TestContexts.flush(ctx);
            return published.stream().flatMap(event -> switch (event) {
                case ManagementAction action -> Stream.of(action);
                default -> Stream.<ManagementAction>empty();
            }).toList();
        }

        @Test
        void everyAction_andEveryRefusal_isRecorded_andPublished_butQueriesAreNot() {
            ClientOrchestrator scoped = scopedTo(Aim.GROUP);

            scoped.getClientNames();
            scoped.getStatusAll();
            scoped.startScript(PIPE_A, WOODCUTTING);
            scoped.startScript(PIPE_B, FISHING);
            scoped.createGroup("New", null);

            List<Entry> entries = ctx.getOrchestratorAudit().entries(MANAGER);
            assertEquals(List.of(
                    List.of("startScript", "Woodcutting on " + PIPE_A, "started"),
                    List.of("startScript", "Fishing on " + PIPE_B, "refused: " + ScopedClientOrchestrator.NOT_IN_TARGETS),
                    List.of("createGroup", "group New",
                            "refused: " + ScopedClientOrchestrator.GROUPS_NEED_WHOLE_HOST)),
                    entries.stream().map(e -> List.of(e.call(), e.target(), e.result())).toList());
            List<ManagementAction> actions = actions();
            assertAll(
                    () -> assertEquals(entries.size(), actions.size()),
                    () -> assertTrue(actions.stream().allMatch(action -> action.script().equals(MANAGER))),
                    () -> assertEquals(entries.stream().map(Entry::result).toList(),
                            actions.stream().map(ManagementAction::result).toList()),
                    () -> assertEquals(entries.stream().map(Entry::at).toList(),
                            actions.stream().map(ManagementAction::at).toList()));
        }

        @Test
        void aCallOverSeveralClients_isOneEntry_countingTheResults() {
            ctx.getManagementTargets().add(MANAGER, Target.host());
            ClientOrchestrator scoped = contextOf(new Manager()).getOrchestrator();

            scoped.startScriptOnAll(FISHING);

            List<Entry> entries = ctx.getOrchestratorAudit().entries(MANAGER);
            assertEquals(1, entries.size());
            assertEquals("3 of 3 succeeded", entries.getFirst().result());
        }

        @Test
        void eachScript_hasItsOwnLog() {
            ClientOrchestrator manager = scopedTo(Aim.WHOLE_HOST);
            ClientOrchestrator other = contextOf(new OtherManager()).getOrchestrator();

            manager.startScript(PIPE_A, WOODCUTTING);
            other.startScript(PIPE_A, FISHING);

            assertAll(
                    () -> assertEquals(1, ctx.getOrchestratorAudit().entries(MANAGER).size()),
                    () -> assertEquals(List.of("refused: " + ScopedClientOrchestrator.NOT_IN_TARGETS),
                            ctx.getOrchestratorAudit().entries(OTHER_MANAGER).stream().map(Entry::result).toList()),
                    () -> assertNotEquals(List.of(), actions()));
        }

        @Test
        void theLogKeepsTheNewestEntries_upToItsCapacity() {
            ClientOrchestrator scoped = scopedTo(Aim.NOT_APPLIED);
            int extra = 5;
            List<String> targets = new ArrayList<>();
            for (int i = 0; i < OrchestratorAuditLog.CAPACITY + extra; i++) {
                String script = "Script" + i;
                targets.add(script + " on " + PIPE_A);
                scoped.stopScript(PIPE_A, script);
            }

            List<Entry> entries = ctx.getOrchestratorAudit().entries(MANAGER);

            assertEquals(targets.subList(extra, targets.size()), entries.stream().map(Entry::target).toList());
        }
    }
}
