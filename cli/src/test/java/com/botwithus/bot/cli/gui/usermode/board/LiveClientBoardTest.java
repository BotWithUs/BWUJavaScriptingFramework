package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.management.ManagementFile;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.runtime.RunnerLiveness;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live board over a real client registry and a real script profile store:
 * one card per client with a row for every script on it, a closed account's
 * card listing what it will resume, and the "Resume after restart" switch
 * writing the account's profile off the render thread.
 */
class LiveClientBoardTest {

    private static final String PIPE = "BotWithUs_14208";
    private static final String UUID = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
    private static final String CLOSED_UUID = "0a6d2f58c3e147b99f045e8b17a2d6c9";
    private static final double MID_JITTER = 0.5;

    @TempDir
    Path tempDir;

    private final Queue<Runnable> commandQueue = new ArrayDeque<>();
    private final Executor queued = commandQueue::add;
    private final BoardRegistry registry = new BoardRegistry(Clock.systemUTC());
    private CliContext ctx;
    private ScriptProfileStore profiles;
    private GroupStore groups;
    private ManagementTargets targets;
    private LiveClientBoard board;

    @BeforeEach
    void setUp() {
        ctx = mock(CliContext.class);
        profiles = new ScriptProfileStore(tempDir.resolve("home"));
        when(ctx.getClientRegistry()).thenReturn(registry.registry);
        when(ctx.getProfileStore()).thenReturn(profiles);
        groups = new GroupStore(new GroupsFile(tempDir.resolve(GroupsFile.FILE_NAME)));
        targets = new ManagementTargets(new ManagementFile(tempDir.resolve(ManagementFile.FILE_NAME)), groups);
        when(ctx.getManagementTargets()).thenReturn(targets);
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(), false), Runnable::run,
                InstantSource.system(), () -> MID_JITTER);
        board = new LiveClientBoard(ctx, id -> { }, Clock.systemUTC(), catalogue,
                new SdnInstaller(tempDir, new InstalledScriptsLedger(tempDir, InstantSource.system())),
                queued);
        drain();
    }

    @Test
    void aClientRunningOneScriptAndHoldingAnother_showsBothRows_eachInItsOwnState() {
        ScriptRunner probe = runner("Location Probe", false);
        ScriptRunner walk = runner("Walk to Flag", true);
        registry.connect(connection(probe, walk), UUID, "Kestrel Moor");

        ClientView view = board.clients().getFirst();

        assertAll(
                () -> assertEquals(List.of("Location Probe", "Walk to Flag"),
                        view.scripts().stream().map(ScriptRow::name).toList()),
                () -> assertEquals(new ScriptState.Stopped(), view.scripts().get(0).state()),
                () -> assertTrue(view.scripts().get(1).state().isRunning()),
                () -> assertEquals(1, view.runningCount()),
                () -> assertTrue(view.isRunning(), "a running script after a stopped one still counts"));
    }

    @Test
    void aScriptAManagementScriptNames_carriesItsRobotLink_butNotForOneThatManagesTheWholeHost() {
        ScriptRunner probe = runner("Location Probe", false);
        ScriptRunner walk = runner("Walk to Flag", true);
        ScriptRunner fish = runner("Fishing", false);
        registry.connect(connection(probe, walk, fish), UUID, "Kestrel Moor");
        GroupId flaggers = groups.create("Flaggers", Optional.empty()).orElseThrow().id();
        groups.addMember(flaggers, ClientKey.account(UUID));
        targets.add("Break Scheduler", new Target.ClientScript(UUID, "Walk to Flag"));
        targets.add("World Balancer", new Target.Group(flaggers));
        targets.add("Restart on Crash", Target.host());

        List<ScriptRow> rows = board.clients().getFirst().scripts();

        assertEquals(List.of(Optional.of("World Balancer"), Optional.of("Break Scheduler"),
                        Optional.of("World Balancer")),
                rows.stream().map(ScriptRow::managedBy).toList(),
                "the most specific first; Restart on Crash covers every script, so it links none");
    }

    @Test
    void aClosedAccount_listsTheScriptsItsProfileWillResume() {
        profiles.setAccountScripts(CLOSED_UUID, List.of("Divination"));
        ClientKey closed = registry.remember(CLOSED_UUID, "Brackenridge");

        board.clients();
        drain();
        ClientView view = board.clients().getFirst();

        assertAll(
                () -> assertEquals(closed, view.id()),
                () -> assertTrue(view.isClosed()),
                () -> assertEquals(List.of("Divination"), view.scripts().stream().map(ScriptRow::name).toList()),
                () -> assertEquals(List.of(new ScriptState.Waiting()),
                        view.scripts().stream().map(ScriptRow::state).toList()),
                () -> assertEquals(new ResumeSwitch.Available(true), view.resume()));
    }

    @Test
    void theResumeSwitch_showsTheNewValueAtOnce_andWritesTheProfileOnTheCommandExecutor() {
        ClientKey account = registry.connect(connection(), UUID, "Oakheart");
        board.clients();
        drain();

        board.actions().setResumeAfterRestart(account, false);

        assertEquals(new ResumeSwitch.Available(false), board.clients().getFirst().resume());
        assertTrue(profiles.isAutoStart(UUID), "not written on the render thread");
        drain();
        assertFalse(profiles.isAutoStart(UUID));
        assertEquals(new ResumeSwitch.Available(false), board.clients().getFirst().resume());
    }

    @Test
    void stopAndRun_actOnTheNamedScriptOnly() {
        ScriptRunner probe = runner("Location Probe", false);
        ScriptRunner walk = runner("Walk to Flag", true);
        ClientKey account = registry.connect(connection(probe, walk), UUID, "Kestrel Moor");

        board.actions().stopScript(account, "Walk to Flag");
        board.actions().runScript(account, "Location Probe");

        verify(walk).stop();
        verify(probe, never()).stop();
        verify(probe).start();
        verify(walk, never()).start();
    }

    private Connection connection(ScriptRunner... runners) {
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of(runners));
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(PIPE);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.isAlive()).thenReturn(true);
        when(ctx.getConnections()).thenReturn(List.of(conn));
        return conn;
    }

    private static ScriptRunner runner(String name, boolean running) {
        ScriptRunner r = mock(ScriptRunner.class);
        RunnerLiveness liveness = new RunnerLiveness();
        when(r.getScriptName()).thenReturn(name);
        when(r.isRunning()).thenReturn(running);
        when(r.lastStartedAt()).thenReturn(running ? Instant.now() : null);
        when(r.livenessState()).thenReturn(liveness);
        when(r.liveness()).thenAnswer(call -> liveness.get());
        when(r.health()).thenReturn(ScriptHealth.HEALTHY);
        when(r.getProfiler()).thenReturn(new ScriptProfiler());
        return r;
    }

    private void drain() {
        Runnable task;
        while ((task = commandQueue.poll()) != null) {
            task.run();
        }
    }
}
