package com.botwithus.bot.cli.gui.pages.groups;

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
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Groups page's manager slot and member robot links, over a real
 * {@link CliContext}: real management runners, targets, per-target settings
 * and group store. Every change runs on the calling thread.
 */
class LiveGroupsManagerTest {

    private static final String BREAKS = "Break Scheduler";
    private static final String WORLDS = "World Balancer";
    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String WOODCUTTING = "Woodcutting";
    private static final String DIVINATION = "Divination";
    private static final int LOOP_MS = 5;
    private static final long AWAIT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final List<ConfigField> FIELDS = List.of(ConfigField.intField("breakEvery", "Break every", 90));

    @ScriptManifest(name = BREAKS, version = "1.2", description = "Staggers breaks.")
    public static final class BreakScheduler implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
        @Override public List<ConfigField> getConfigFields() { return FIELDS; }
    }

    @ScriptManifest(name = WORLDS, version = "0.2")
    public static final class WorldBalancer implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private final List<InspectorRequest> inspected = new ArrayList<>();
    private final List<String> openedManagement = new ArrayList<>();
    private CliContext ctx;
    private LiveGroupsModel model;
    private GroupId woodcutters;

    @BeforeEach
    void host() {
        ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));
        ctx.initManagementRuntime();
        ctx.getManagementRuntime().registerScript(new BreakScheduler());
        ctx.getManagementRuntime().registerScript(new WorldBalancer());
        woodcutters = TestContexts.createGroup(ctx, "Woodcutters");
        TestContexts.addMember(ctx, "Woodcutters", UUID_A);
        TestContexts.addMember(ctx, "Woodcutters", UUID_B);
        model = new LiveGroupsModel(ctx, List::of, Runnable::run, inspected::add, openedManagement::add,
                Clock.systemUTC());
    }

    @AfterEach
    void stopScripts() {
        ctx.getManagementRuntime().stopAll();
    }

    private ManagementScriptRunner runner(String name) {
        return ctx.getManagementRuntime().findRunner(name);
    }

    private Optional<ManagerSlot> slot() {
        return ctx.getGroupStore().get(woodcutters).flatMap(ClientGroup::manager);
    }

    @Test
    void assigning_makesTheScriptTheGroupsOneManager_andStartsItWhenAsked() {
        model.assignManager(woodcutters, BREAKS, true);
        boolean isBreaksRunning = runner(BREAKS).isRunning();
        model.assignManager(woodcutters, WORLDS, false);

        assertAll(
                () -> assertTrue(isBreaksRunning, "Start it now"),
                () -> assertEquals(Optional.of(new ManagerSlot(WORLDS, true)), slot()),
                () -> assertEquals(List.of(new Target.Group(woodcutters)),
                        ctx.getManagementTargets().targetsOf(WORLDS)),
                () -> assertEquals(List.of(), ctx.getManagementTargets().targetsOf(BREAKS), "one manager per group"),
                () -> assertFalse(runner(WORLDS).isRunning(), "not asked to start"),
                () -> assertTrue(model.notice().orElseThrow().text().contains(WORLDS)));
    }

    @Test
    void theSlot_saysWhetherTheManagerManages_isPausedByStopAll_andStartsAgain() {
        Optional<ManagerInfo> none = model.manager(woodcutters);
        model.assignManager(woodcutters, BREAKS, true);
        ManagerInfo managing = model.manager(woodcutters).orElseThrow();

        model.stopAll(woodcutters);
        ManagerInfo paused = model.manager(woodcutters).orElseThrow();
        model.startManager(woodcutters);

        assertAll(
                () -> assertEquals(Optional.empty(), none),
                () -> assertEquals(ManagerInfo.State.MANAGING, managing.state()),
                () -> assertEquals("1.2", managing.version()),
                () -> assertTrue(managing.hasSettings()),
                () -> assertTrue(managing.line(2).startsWith("Running "), managing.line(2)),
                () -> assertTrue(managing.line(2).contains("sees only these 2 clients"), managing.line(2)),
                () -> assertEquals(ManagerInfo.State.PAUSED, paused.state()),
                () -> assertEquals(ManagerInfo.State.MANAGING, model.manager(woodcutters).orElseThrow().state()),
                () -> assertEquals(Optional.of(new ManagerSlot(BREAKS, true)), slot()));
    }

    @Test
    void aStoppedManager_isStopped_andOneNotLoaded_saysSo() throws Exception {
        model.assignManager(woodcutters, BREAKS, true);
        ctx.getManagementControl().stop(BREAKS);
        assertTrue(runner(BREAKS).awaitStop(AWAIT_MS));
        ManagerInfo.State stopped = model.manager(woodcutters).orElseThrow().state();
        ctx.getGroupStore().setManager(woodcutters, Optional.of(new ManagerSlot("Gone Script", true)));

        assertAll(
                () -> assertEquals(ManagerInfo.State.STOPPED, stopped),
                () -> assertEquals(ManagerInfo.State.NOT_LOADED, model.manager(woodcutters).orElseThrow().state()));
    }

    @Test
    void theAssignDialog_listsLoadedScripts_withTheGroupsEachAlreadyManages() {
        model.assignManager(woodcutters, BREAKS, false);

        assertEquals(List.of(
                new ManagerChoice(BREAKS, "1.2", "Staggers breaks.", List.of("Woodcutters")),
                new ManagerChoice(WORLDS, "0.2", "", List.of())), model.managers());
    }

    @Test
    void settings_openTheInspectorOnTheManagersSettingsForThisGroup() {
        model.openManagerSettings(woodcutters);
        model.assignManager(woodcutters, BREAKS, false);
        model.openManagerSettings(woodcutters);

        assertEquals(List.of(new InspectorRequest(
                new InspectorSubject.ManagementScript(BREAKS, Optional.of(new Target.Group(woodcutters))),
                InspectorTab.SETTINGS)), inspected, "nothing to open before a manager is assigned");
    }

    @Test
    void aMemberTargetedOnItsOwn_linksToItsManagementScript_inTheDesignsWords() {
        model.assignManager(woodcutters, BREAKS, false);
        Target.ClientScript aWoodcutting = new Target.ClientScript(UUID_A, WOODCUTTING);
        ctx.getManagementTargets().add(BREAKS, aWoodcutting);
        ctx.getManagementTargets().add(BREAKS, new Target.ClientScript(UUID_B, WOODCUTTING));
        ctx.getManagementTargets().add(WORLDS, new Target.ClientScript(UUID_B, DIVINATION));
        ctx.getManagementSettings().apply(BREAKS, aWoodcutting, new ScriptConfig(Map.of("breakEvery", "30")), FIELDS);

        assertAll(
                () -> assertEquals(MemberManagement.OWN_SETTINGS,
                        model.memberManagement(woodcutters, UUID_A, WOODCUTTING).orElseThrow().label()),
                () -> assertEquals(MemberManagement.ALSO_DIRECT,
                        model.memberManagement(woodcutters, UUID_B, WOODCUTTING).orElseThrow().label()),
                () -> assertEquals(WORLDS, model.memberManagement(woodcutters, UUID_B, DIVINATION).orElseThrow()
                        .label(), "another script, named"),
                () -> assertEquals(Optional.empty(), model.memberManagement(woodcutters, UUID_A, DIVINATION),
                        "only through the group: no link"));
    }

    @Test
    void aRobotLink_opensManagement() {
        model.openManagement(BREAKS);

        assertEquals(List.of(BREAKS), openedManagement);
    }
}
