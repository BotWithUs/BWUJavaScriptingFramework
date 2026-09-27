package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botwithus.bot.cli.TestContexts.connectIdentified;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Groups page's model over a real {@link CliContext}: real groups file, real
 * runtimes and scripts, real start-when-back queue. Only the agents are fakes,
 * and every change runs on the calling thread so its effect is visible at once.
 */
class LiveGroupsModelTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_B = "BotWithUs_2002";
    private static final String WOODCUTTER = "Woodcutter";
    private static final String DIVINER = "Diviner";
    private static final String MANAGER = "Break Scheduler";
    private static final int LOOP_MS = 50;

    @ScriptManifest(name = WOODCUTTER, version = "1.0", author = "test")
    public static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = DIVINER, version = "1.0", author = "test")
    public static final class Diviner implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
    private final List<ScriptRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void stopScripts() {
        runtimes.forEach(ScriptRuntime::stopAll);
    }

    private CliContext context() {
        return TestContexts.inDirInLine(dir, discard);
    }

    private static LiveGroupsModel model(CliContext ctx) {
        return new LiveGroupsModel(ctx, List::of, Runnable::run);
    }

    /** A client runtime with both scripts installed and none running. */
    private ScriptRuntime runtime(String pipe) {
        ScriptRuntime runtime = TestContexts.runtime(pipe);
        runtime.registerScript(new Woodcutter());
        runtime.registerScript(new Diviner());
        runtimes.add(runtime);
        return runtime;
    }

    private static boolean isRunning(ScriptRuntime runtime, String script) {
        return runtime.findRunner(script).isRunning();
    }

    private static ClientGroup group(CliContext ctx, GroupId id) {
        return ctx.getGroupStore().get(id).orElseThrow();
    }

    @Test
    void stopAll_pausesTheManager_andStopsEveryMembersScripts() {
        CliContext ctx = context();
        ScriptRuntime runtime = runtime(PIPE_A);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);
        runtime.findRunner(WOODCUTTER).start();
        GroupId id = TestContexts.createGroup(ctx, "Woodcutters");
        TestContexts.addMember(ctx, "Woodcutters", UUID_A);
        TestContexts.addMember(ctx, "Woodcutters", UUID_B);
        ctx.startWhenBack(UUID_B, DIVINER);
        ctx.getGroupStore().setManager(id, Optional.of(new ManagerSlot(MANAGER, true)));

        model(ctx).stopAll(id);

        assertAll(
                () -> assertFalse(isRunning(runtime, WOODCUTTER)),
                () -> assertEquals(List.of(), ctx.getStartWhenBackQueue().all(), "the queued start is cancelled"),
                () -> assertEquals(Optional.of(new ManagerSlot(MANAGER, false)), group(ctx, id).manager()));
    }

    @Test
    void stopAll_saysTheManagerIsPaused_andLeavesAPausedOneAlone() {
        CliContext ctx = context();
        GroupId running = TestContexts.createGroup(ctx, "Running");
        GroupId paused = TestContexts.createGroup(ctx, "Paused");
        ctx.getGroupStore().setManager(running, Optional.of(new ManagerSlot(MANAGER, true)));
        ctx.getGroupStore().setManager(paused, Optional.of(new ManagerSlot(MANAGER, false)));
        LiveGroupsModel model = model(ctx);

        model.stopAll(running);
        String first = model.notice().orElseThrow().text();
        model.stopAll(paused);
        String second = model.notice().orElseThrow().text();

        assertAll(
                () -> assertTrue(first.contains("manager is paused"), first),
                () -> assertFalse(second.contains("manager"), second),
                () -> assertEquals(Optional.of(new ManagerSlot(MANAGER, false)), group(ctx, paused).manager()));
    }

    @Test
    void addMembers_ofAClientWithNoAccount_reportsWhyItWasRefused() {
        CliContext ctx = context();
        GroupId id = TestContexts.createGroup(ctx, "Woodcutters");
        ClientKey pipeKey = new ClientKey.Pipe(PIPE_B);
        String reason = MemberChange.refusalFor(pipeKey).orElseThrow().reason();
        LiveGroupsModel model = model(ctx);

        model.addMembers(id, List.of(pipeKey));

        Notice notice = model.notice().orElseThrow();
        assertAll(
                () -> assertTrue(notice.isProblem()),
                () -> assertTrue(notice.text().contains(reason), notice.text()),
                () -> assertEquals(List.of(), group(ctx, id).members()));
    }

    /**
     * The old panel's Add combo lost its selection every frame and always added
     * the first connection listed. Tick the second one, draw a few frames, and
     * the second one is the one added.
     */
    @Test
    void addClients_addsTheTickedClient_notTheFirstOneListed() {
        CliContext ctx = context();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime(PIPE_A));
        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo", runtime(PIPE_B));
        GroupId id = TestContexts.createGroup(ctx, "Woodcutters");
        LiveGroupsModel model = model(ctx);
        TickedKeys<GroupId> ticks = new TickedKeys<>();

        List<PickableClient> listed = candidates(model, id);
        ticks.toggle(id, listed.get(1).key().value());
        candidates(model, id);
        List<String> ticked = ticks.ticked(id, keysOf(candidates(model, id)));
        model.addMembers(id, candidates(model, id).stream()
                .filter(client -> ticked.contains(client.key().value()))
                .map(PickableClient::key)
                .toList());

        assertAll(
                () -> assertEquals(List.of(UUID_A, UUID_B), listed.stream()
                        .map(client -> client.accountUuid().orElseThrow()).toList()),
                () -> assertEquals(List.of(UUID_B), group(ctx, id).members()));
    }

    @Test
    void removeMembers_removesAnUnresolvedMember_byItsPipeKey() throws IOException {
        ClientGroup legacy = new ClientGroup(GroupId.random(), "Old", Optional.empty(), List.of(UUID_A),
                List.of(PIPE_B), Optional.empty());
        new GroupsFile(dir.resolve(GroupsFile.FILE_NAME)).write(List.of(legacy));
        CliContext ctx = context();
        ctx.loadGroups();
        LiveGroupsModel model = model(ctx);
        MemberRow unresolved = GroupViews.detail(legacy, model.snapshot()).rows().getLast();

        model.removeMembers(legacy.id(), List.of(unresolved.key()));

        assertAll(
                () -> assertEquals(new MemberRow.Unresolved(PIPE_B), unresolved),
                () -> assertEquals(List.of(), group(ctx, legacy.id()).unresolved()),
                () -> assertEquals(List.of(UUID_A), group(ctx, legacy.id()).members()));
    }

    @Test
    void snapshot_readsEachMembersRunnersAndQueue() {
        CliContext ctx = context();
        ScriptRuntime runtime = runtime(PIPE_A);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);
        runtime.findRunner(WOODCUTTER).start();
        runtime.findRunner(DIVINER).start();
        runtime.stopScript(DIVINER);
        ctx.startWhenBack(UUID_B, WOODCUTTER);
        GroupId id = TestContexts.createGroup(ctx, "Both");
        TestContexts.addMember(ctx, "Both", UUID_A);
        TestContexts.addMember(ctx, "Both", UUID_B);

        GroupsSnapshot snapshot = model(ctx).snapshot();

        MemberFacts alpha = snapshot.facts(UUID_A);
        MemberFacts bravo = snapshot.facts(UUID_B);
        assertAll(
                () -> assertEquals(MemberLink.CONNECTED, alpha.link()),
                () -> assertEquals(Optional.of("Alpha"), alpha.name()),
                () -> assertEquals(List.of(ScriptState.RUNNING, ScriptState.STOPPED),
                        alpha.scripts().stream().map(ScriptFact::state).toList()),
                () -> assertEquals(MemberLink.CLOSED, bravo.link()),
                () -> assertEquals(List.of(ScriptFact.of(WOODCUTTER, ScriptState.QUEUED)), bravo.scripts()));
    }

    @Test
    void stopMembers_cancelsTheStartQueuedForAClosedMember() {
        CliContext ctx = context();
        ctx.startWhenBack(UUID_B, WOODCUTTER);

        model(ctx).stopMembers(List.of(UUID_B));

        assertEquals(List.of(), ctx.getStartWhenBackQueue().all());
    }

    @Test
    void startScript_startsOnConnectedMembers_andQueuesItForTheRest() {
        CliContext ctx = context();
        ScriptRuntime runtime = runtime(PIPE_A);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime);
        GroupId id = TestContexts.createGroup(ctx, "Woodcutters");
        TestContexts.addMember(ctx, "Woodcutters", UUID_A);
        TestContexts.addMember(ctx, "Woodcutters", UUID_B);
        LiveGroupsModel model = model(ctx);

        model.startScript(id, WOODCUTTER, BusyChoice.ALSO_START);

        assertAll(
                () -> assertTrue(isRunning(runtime, WOODCUTTER)),
                () -> assertEquals(List.of(UUID_B), ctx.getStartWhenBackQueue().all().stream()
                        .map(queued -> queued.accountUuid()).toList()),
                () -> assertEquals("Starting Woodcutter on 1 client. 1 client starts it when back.",
                        model.notice().orElseThrow().text()));
    }

    @Test
    void startScript_onABusyMember_stopsWhatItRuns_onlyWhenSwitching() {
        CliContext ctx = context();
        ScriptRuntime alsoRuntime = runtime(PIPE_A);
        ScriptRuntime switchRuntime = runtime(PIPE_B);
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", alsoRuntime);
        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo", switchRuntime);
        alsoRuntime.findRunner(DIVINER).start();
        switchRuntime.findRunner(DIVINER).start();
        GroupId also = TestContexts.createGroup(ctx, "Also");
        GroupId swap = TestContexts.createGroup(ctx, "Switch");
        TestContexts.addMember(ctx, "Also", UUID_A);
        TestContexts.addMember(ctx, "Switch", UUID_B);
        LiveGroupsModel model = model(ctx);

        model.startScript(also, WOODCUTTER, BusyChoice.ALSO_START);
        model.startScript(swap, WOODCUTTER, BusyChoice.SWITCH);

        assertAll(
                () -> assertTrue(isRunning(alsoRuntime, WOODCUTTER)),
                () -> assertTrue(isRunning(alsoRuntime, DIVINER), "also start leaves it running"),
                () -> assertTrue(isRunning(switchRuntime, WOODCUTTER)),
                () -> assertFalse(isRunning(switchRuntime, DIVINER), "a switch stops it"));
    }

    @Test
    void createGroup_withATakenName_saysSo_andMakesNothing() {
        CliContext ctx = context();
        TestContexts.createGroup(ctx, "Woodcutters");
        LiveGroupsModel model = model(ctx);

        model.createGroup(" Woodcutters ", List.of());

        assertAll(
                () -> assertTrue(model.notice().orElseThrow().isProblem()),
                () -> assertEquals(1, ctx.getGroupStore().all().size()),
                () -> assertEquals(Optional.empty(), model.takeCreated()));
    }

    @Test
    void createGroup_addsTheTickedClients_andHandsBackTheNewGroupOnce() {
        CliContext ctx = context();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha", runtime(PIPE_A));
        LiveGroupsModel model = model(ctx);

        model.createGroup("Yews", List.of(ClientKey.account(UUID_A)));

        GroupId created = model.takeCreated().orElseThrow();
        assertAll(
                () -> assertEquals(List.of(UUID_A), group(ctx, created).members()),
                () -> assertEquals(Optional.empty(), model.takeCreated()));
    }

    private static List<PickableClient> candidates(LiveGroupsModel model, GroupId id) {
        GroupsSnapshot snapshot = model.snapshot();
        return GroupViews.candidates(snapshot, snapshot.group(id), "");
    }

    private static List<String> keysOf(List<PickableClient> clients) {
        return clients.stream().map(client -> client.key().value()).toList();
    }
}
