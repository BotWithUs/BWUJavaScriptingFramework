package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.runtime.ScriptHealth;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.clients.ClientStore;
import com.botwithus.bot.cli.clients.RememberedClient;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogEntry;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveDashboardModelTest {

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double EPSILON = 1e-9;
    private static final Instant T0 = Instant.parse("2026-09-26T14:00:00Z");
    private static final String OAKHEART = "BotWithUs_14208";
    private static final String FERNMOSS = "BotWithUs_9932";
    /** The pipe Oakheart's account was on before its game was restarted. */
    private static final String OAKHEART_BEFORE = "BotWithUs_12001";
    private static final String OAKHEART_UUID = "0f6b3c521d7e4a8e9b1f5c2d7e8a9b10";
    private static final ClientKey OAKHEART_ACCOUNT = ClientKey.account(OAKHEART_UUID);
    private static final ClientKey FERNMOSS_ACCOUNT = ClientKey.account("5a1c9e2b7d3f4e6a8b0c1d2e3f4a5b6c");
    private static final String QUERY = "query_entities";
    private static final int SAMPLES_PER_CLIENT = 50;

    private final CliContext ctx = mock(CliContext.class);
    private final ClientActions clientActions = mock(ClientActions.class);
    private final ConnectionHistory history = new ConnectionHistory();
    private final List<RememberedClient> remembered = new ArrayList<>();
    private final ClientRegistry registry = new ClientRegistry(new ClientStore() {
        @Override
        public List<RememberedClient> load() {
            return List.copyOf(remembered);
        }

        @Override
        public void save(List<RememberedClient> clients) { }
    }, pipe -> Optional.empty(), history, event -> { }, Clock.fixed(T0, ZoneOffset.UTC), Runnable::run);
    private final LogBuffer logBuffer = new LogBuffer();
    private final List<Connection> connections = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Instant now = T0;
    private LiveDashboardModel model;

    @BeforeEach
    void setUp() {
        when(ctx.getConnections()).thenReturn(connections);
        when(ctx.getConnectionHistory()).thenReturn(history);
        when(ctx.getClientRegistry()).thenReturn(registry);
        when(ctx.getLogBuffer()).thenReturn(logBuffer);
        when(ctx.getLastLoadReport()).thenReturn(LoadReport.EMPTY);
        InstantSource clock = () -> now;
        CommandConsole console = new CommandConsole(new AnsiOutputBuffer(), new CommandRegistry(), executor,
                ctx, () -> { });
        model = new LiveDashboardModel(ctx, console, "scripts/", r -> { }, clientActions, clock);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ── RPC latency ─────────────────────────────────────────────────────

    @Test
    void rpc_poolsEveryClientsSamplesPerMethod_insteadOfShowingOneClient() {
        connect(OAKHEART, latencies(1), List.of());
        connect(FERNMOSS, latencies(SAMPLES_PER_CLIENT + 1), List.of());

        RpcRow row = model.view(Scope.ALL).rpc().getFirst();

        assertAll(
                () -> assertEquals(QUERY, row.method()),
                () -> assertEquals(2L * SAMPLES_PER_CLIENT, row.calls()),
                () -> assertEquals(50.0, row.p50Ms(), EPSILON),
                () -> assertEquals(95.0, row.p95Ms(), EPSILON),
                () -> assertEquals(99.0, row.p99Ms(), EPSILON));
    }

    @Test
    void rpc_scopedToOneClient_usesThatClientsSamplesOnly() {
        connect(OAKHEART, latencies(1), List.of());
        connect(FERNMOSS, latencies(SAMPLES_PER_CLIENT + 1), List.of());

        RpcRow row = model.view(Scope.of(FERNMOSS)).rpc().getFirst();

        assertEquals(SAMPLES_PER_CLIENT, row.calls());
        assertEquals(75.0, row.p50Ms(), EPSILON);
    }

    // ── Needs attention ─────────────────────────────────────────────────

    @Test
    void attention_listsWhatIsWrongNow_errorsFirstThenNewestFirst() {
        ScriptRunner crashed = crashed("Cook's Assistant", at(10), at(20));
        ScriptRunner stalled = runner("Divination", Liveness.STALLED, true, ScriptHealth.HEALTHY);
        ScriptRunner restartedSinceItsCrash = crashed("Walk to Flag", at(40), at(30));
        connect(OAKHEART, new RpcMetrics(), List.of(crashed, stalled, restartedSinceItsCrash));
        Connection lost = connect(FERNMOSS, new RpcMetrics(), List.of());
        when(lost.currentReconnectState()).thenReturn(new ReconnectState.Reconnecting(0L, 4, 8_000L));
        Path jar = Path.of("scripts", "woodcutting-1.0.jar");
        when(ctx.getLastLoadReport()).thenReturn(failedToLoad(jar));
        history.accept(new HostEvent.ScriptLoadFailed(jar, new IllegalStateException(), at(25)));
        history.accept(new HostEvent.ScriptStalled(new ClientRef(OAKHEART), "Divination", at(50)));
        history.accept(new HostEvent.ConnectionLost(new ClientRef(FERNMOSS), null, at(45)));

        List<AttentionItem> items = model.view(Scope.ALL).attention();

        assertEquals(List.of("jar:" + jar, "crash:" + OAKHEART + ":Cook's Assistant",
                "stall:" + OAKHEART + ":Divination", "conn:" + FERNMOSS), keys(items));
        assertEquals(4, attemptOf(items.get(3)));
    }

    @Test
    void attention_scopedToOneClient_leavesOutOtherClientsAndHostWideFailures() {
        ScriptRunner crashed = crashed("Cook's Assistant", at(10), at(20));
        connect(OAKHEART, new RpcMetrics(), List.of(crashed));
        Connection lost = connect(FERNMOSS, new RpcMetrics(), List.of());
        when(lost.currentReconnectState()).thenReturn(new ReconnectState.GivingUp(0L, 5, null));
        when(ctx.getLastLoadReport()).thenReturn(failedToLoad(Path.of("scripts", "broken.jar")));

        assertEquals(List.of("gaveup:" + FERNMOSS), keys(model.view(Scope.of(FERNMOSS)).attention()));
    }

    /**
     * "Reconnect now" and "Try again" are the board's Retry: they wake or restart
     * the client's own recovery, which rebuilds the connection itself only when
     * there is nothing to retry, rather than always tearing it down. The board is
     * keyed by account, so the pipe is handed over as the key it is known by now.
     */
    @Test
    void reconnectControls_retryThroughTheBoard_ratherThanTearingTheConnectionDown() {
        when(ctx.clientKeyOf(FERNMOSS)).thenReturn(FERNMOSS_ACCOUNT);

        model.actions().retryNow(FERNMOSS);

        verify(clientActions).retryNow(FERNMOSS_ACCOUNT);
        verify(clientActions, never()).reconnect(any());
    }

    // ── Loop thresholds ─────────────────────────────────────────────────

    @Test
    void runnerRow_flagsAWorstLoopOverFiveTimesTheAverage_butNotExactlyFive() {
        ScriptRunner outlier = runner("Outlier", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        ScriptRunner atTheLine = runner("AtTheLine", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        loops(outlier, 5, 5, 5, 5, 5, 5, 5, 5, 5, 55);
        loops(atTheLine, 0, 0, 0, 0, 50);
        connect(OAKHEART, new RpcMetrics(), List.of(outlier, atTheLine));

        List<RunnerRow> rows = model.view(Scope.ALL).runners();

        assertTrue(row(rows, "Outlier").isMaxOutlier(), "55 ms against a 10 ms average");
        assertFalse(row(rows, "AtTheLine").isMaxOutlier(), "50 ms is exactly 5x a 10 ms average");
    }

    @Test
    void runnerRow_marksTheLastLoopAmberOverTwiceTheAverage_butNotExactlyTwice() {
        ScriptRunner spiking = runner("Spiking", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        ScriptRunner atTheLine = runner("AtTheLine", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        loops(spiking, 10, 10, 10, 31);
        loops(atTheLine, 10, 10, 10, 30);
        connect(OAKHEART, new RpcMetrics(), List.of(spiking, atTheLine));

        List<RunnerRow> rows = model.view(Scope.ALL).runners();

        assertTrue(row(rows, "Spiking").isLastHot(), "31 ms against a 15.25 ms average");
        assertFalse(row(rows, "AtTheLine").isLastHot(), "30 ms is exactly 2x a 15 ms average");
    }

    @Test
    void runnerRows_listProblemsFirst() {
        ScriptRunner running = runner("Alpha", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        ScriptRunner stalled = runner("Beta", Liveness.STALLED, true, ScriptHealth.HEALTHY);
        ScriptRunner crashed = crashed("Gamma", at(1), at(2));
        connect(OAKHEART, new RpcMetrics(), List.of(running, stalled, crashed));

        List<RunnerStatus> order = model.view(Scope.ALL).runners().stream().map(RunnerRow::status).toList();

        assertEquals(List.of(RunnerStatus.CRASHED, RunnerStatus.STALLED, RunnerStatus.RUNNING), order);
    }

    // ── Logs and events ─────────────────────────────────────────────────

    @Test
    void logs_scopedToAClient_showOnlyItsLines_andCountOnlyItsErrors() {
        logBuffer.add(new LogEntry("Woodcutting", "INFO", "Banked logs", OAKHEART));
        logBuffer.add(new LogEntry("Divination", "ERROR", "Crashed", FERNMOSS));
        logBuffer.add(new LogEntry("ScriptLoader", "ERROR", "Bad JAR", null));
        logBuffer.add(new LogEntry("Woodcutting", "ERROR", "Tree gone", OAKHEART));

        LogsView oakheart = model.logs(Scope.of(OAKHEART), LogLevel.ALL);
        LogsView oakheartInfo = model.logs(Scope.of(OAKHEART), LogLevel.INFO);
        LogsView everyone = model.logs(Scope.ALL, LogLevel.ALL);

        assertAll(
                () -> assertEquals(List.of("Banked logs", "Tree gone"), messages(oakheart)),
                () -> assertEquals(1, oakheart.errors()),
                () -> assertEquals(List.of("Banked logs"), messages(oakheartInfo)),
                () -> assertEquals(1, oakheartInfo.errors(), "the badge ignores the level chip"),
                () -> assertEquals(4, everyone.lines().size()),
                () -> assertEquals(3, everyone.errors()));
    }

    @Test
    void events_scopedToAClient_leaveOutOtherClientsAndHostWideEvents() {
        history.accept(new HostEvent.ClientOpened(new ClientRef(OAKHEART), at(1)));
        history.accept(new HostEvent.ClientOpened(new ClientRef(FERNMOSS), at(2)));
        history.accept(new HostEvent.ScriptLoadFailed(Path.of("a.jar"), new IllegalStateException(), at(3)));
        history.accept(new HostEvent.ScriptStalled(new ClientRef(OAKHEART), "Divination", at(4)));

        List<String> scoped = model.events(Scope.of(OAKHEART)).stream().map(EventRow::type).toList();
        List<String> all = model.events(Scope.ALL).stream().map(EventRow::type).toList();

        assertEquals(List.of("ClientOpened", "ScriptStalled"), scoped);
        assertEquals(List.of("ClientOpened", "ClientOpened", "ScriptLoadFailed", "ScriptStalled"), all);
    }

    /**
     * A client's history follows its account across pipes, so a client scoped
     * by its current pipe also shows what it did before its game was restarted.
     */
    @Test
    void events_scopedToAClient_includeWhatItsAccountDidOnAnEarlierPipe() {
        history.accept(new HostEvent.ClientOpened(new ClientRef(OAKHEART_BEFORE), at(1)));
        history.accept(new HostEvent.ClientIdentified(new ClientRef(OAKHEART_ACCOUNT, OAKHEART_BEFORE),
                Optional.empty(), at(2)));
        history.accept(new HostEvent.ScriptStopped(new ClientRef(OAKHEART_ACCOUNT, OAKHEART_BEFORE),
                "Divination", at(3)));
        history.accept(new HostEvent.ClientOpened(new ClientRef(FERNMOSS), at(4)));
        history.accept(new HostEvent.ClientOpened(new ClientRef(OAKHEART), at(5)));
        history.accept(new HostEvent.ClientIdentified(new ClientRef(OAKHEART_ACCOUNT, OAKHEART),
                Optional.empty(), at(6)));

        List<String> scoped = model.events(Scope.of(OAKHEART)).stream().map(EventRow::type).toList();

        assertEquals(List.of("ClientOpened", "ClientIdentified", "ScriptStopped", "ClientOpened",
                "ClientIdentified"), scoped);
    }

    @Test
    void events_ofAClientWithNoConnection_areLabelledWithTheNameTheRegistryHasForIt() {
        remembered.add(new RememberedClient(OAKHEART_UUID, Optional.of("Tamsin Vale"), OptionalInt.empty(), T0));
        registry.load();
        history.accept(new HostEvent.ScriptStopped(new ClientRef(OAKHEART_ACCOUNT, OAKHEART_BEFORE),
                "Divination", at(1)));

        assertEquals("Tamsin Vale", model.events(Scope.ALL).getFirst().clientLabel());
    }

    @Test
    void events_describeAClientComingBackOnANewPipe() {
        history.accept(new HostEvent.ClientResumed(new ClientRef(OAKHEART_ACCOUNT, OAKHEART),
                Optional.of(OAKHEART_BEFORE), at(1)));
        history.accept(new HostEvent.ClientResumed(new ClientRef(OAKHEART_ACCOUNT, OAKHEART),
                Optional.empty(), at(2)));

        List<EventRow> rows = model.events(Scope.ALL);

        assertAll(
                () -> assertEquals("ClientResumed", rows.getFirst().type()),
                () -> assertEquals("Back from " + OAKHEART_BEFORE, rows.getFirst().detail()),
                () -> assertEquals("Back from an earlier session", rows.get(1).detail()));
    }

    @Test
    void events_ofALiveClient_areLabelledAsTheRestOfTheDashboardLabelsIt() {
        Connection live = connect(OAKHEART, new RpcMetrics(), List.of());
        when(live.getDisplayName()).thenReturn(Optional.of("Oakheart"));
        history.accept(new HostEvent.ClientOpened(new ClientRef(OAKHEART), at(1)));

        assertEquals("Oakheart", model.events(Scope.ALL).getFirst().clientLabel());
    }

    // ── Reset ───────────────────────────────────────────────────────────

    @Test
    void resetMetrics_zeroesEveryClientsFigures_andRestartsTheClock() {
        ScriptRunner runner = runner("Alpha", Liveness.LIVE, true, ScriptHealth.HEALTHY);
        loops(runner, 10, 20);
        connect(OAKHEART, latencies(1), List.of(runner));
        connect(FERNMOSS, latencies(1), List.of());
        now = T0.plus(Duration.ofMinutes(6));

        model.actions().resetMetrics();
        DashboardView view = model.view(Scope.ALL);

        assertAll(
                () -> assertEquals(now, view.since()),
                () -> assertTrue(view.isReset()),
                () -> assertTrue(view.rpc().isEmpty()),
                () -> assertEquals(0L, view.runners().getFirst().loops()));
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private Connection connect(String pipe, RpcMetrics metrics, List<ScriptRunner> runners) {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.getMetrics()).thenReturn(metrics);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(runners);
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(pipe);
        when(conn.getRpc()).thenReturn(rpc);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.isAlive()).thenReturn(true);
        for (ScriptRunner runner : runners) {
            String name = runner.getScriptName();
            when(runtime.findRunner(name)).thenReturn(runner);
        }
        connections.add(conn);
        return conn;
    }

    /** {@value #SAMPLES_PER_CLIENT} calls of {@code first}, {@code first + 1}, … ms. */
    private static RpcMetrics latencies(int first) {
        RpcMetrics metrics = new RpcMetrics();
        for (int i = 0; i < SAMPLES_PER_CLIENT; i++) {
            metrics.recordCall(QUERY, (first + i) * NANOS_PER_MS, false);
        }
        return metrics;
    }

    private static ScriptRunner runner(String name, Liveness liveness, boolean isRunning, ScriptHealth health) {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.getScriptName()).thenReturn(name);
        when(runner.liveness()).thenReturn(liveness);
        when(runner.isRunning()).thenReturn(isRunning);
        when(runner.health()).thenReturn(health);
        when(runner.lastStartedAt()).thenReturn(T0);
        when(runner.getProfiler()).thenReturn(new ScriptProfiler());
        return runner;
    }

    /** A stopped runner last started at {@code startedAt} whose last crash was at {@code crashedAt}. */
    private static ScriptRunner crashed(String name, Instant startedAt, Instant crashedAt) {
        ScriptHealth health = ScriptHealth.HEALTHY.withCrash(
                new LastCrash(Phase.ON_LOOP, 0L, crashedAt, new NullPointerException()));
        ScriptRunner runner = runner(name, Liveness.LIVE, false, health);
        when(runner.lastStartedAt()).thenReturn(startedAt);
        return runner;
    }

    private static int attemptOf(AttentionItem item) {
        return switch (item) {
            case AttentionItem.NotResponding n -> n.attempt();
            case AttentionItem.GaveUp g -> g.attempts();
            case AttentionItem.Stalled _, AttentionItem.Crashed _, AttentionItem.CutOff _,
                 AttentionItem.LoadFailed _ -> -1;
        };
    }

    private static void loops(ScriptRunner runner, long... millis) {
        for (long ms : millis) {
            runner.getProfiler().recordLoop(ms * NANOS_PER_MS);
        }
    }

    private static LoadReport failedToLoad(Path jar) {
        return new LoadReport(List.of(ScriptLoadResult.failure(jar, new IllegalStateException("no provider"),
                List.of())));
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static RunnerRow row(List<RunnerRow> rows, String script) {
        return rows.stream().filter(r -> r.script().equals(script)).findFirst().orElseThrow();
    }

    private static List<String> keys(List<AttentionItem> items) {
        return items.stream().map(AttentionItem::key).toList();
    }

    private static List<String> messages(LogsView view) {
        return view.lines().stream().map(LogEntry::message).toList();
    }
}
