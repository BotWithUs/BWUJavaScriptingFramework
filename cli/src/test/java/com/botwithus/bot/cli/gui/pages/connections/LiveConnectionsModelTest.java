package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.clients.ClientStore;
import com.botwithus.bot.cli.clients.LiveClient;
import com.botwithus.bot.cli.clients.RememberedClient;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.rpc.ReconnectPolicy;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live model over a host whose client registry, settings and profile store
 * are real; only the context and the connections around them are mocks. Work
 * that must stay off the render thread goes to executors the test drains by hand.
 */
class LiveConnectionsModelTest {

    private static final Instant NOW = Instant.parse("2026-09-26T14:06:00Z");
    private static final String PIPE = "BotWithUs_14208";
    private static final String OTHER_PIPE = "BotWithUs_9932";
    private static final String FOUND_PIPE = "BotWithUs_19544";
    private static final String UUID = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
    private static final ClientKey KEY = ClientKey.account(UUID);
    private static final String NAME = "Oakheart";
    private static final String GROUP = "Woodcutters";
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long FAST_MS = 2L;
    private static final long SLOW_MS = 4L;
    private static final long RETRY_MS = 500L;

    @TempDir
    Path tempDir;

    /** Stands in for the connect command: records what it was asked, answers a canned scan. */
    private static final class FakeCommands implements LiveConnectionsModel.PipeCommands {
        final List<String> scannedPrefixes = new ArrayList<>();
        final List<String> connected = new ArrayList<>();
        List<FoundPipe> scanReply = List.of();

        @Override
        public LiveConnectionsModel.ScanOutcome scan(String prefix) {
            scannedPrefixes.add(prefix);
            return new LiveConnectionsModel.ScanOutcome(scanReply, "Found " + scanReply.size() + " pipe(s).");
        }

        @Override
        public void connect(String pipe) {
            connected.add(pipe);
        }
    }

    /** A connection's readings, as the registry sees them. */
    private record FakeLink(Instant connectedAt, GameStatus gameStatus) implements LiveClient {
        @Override public Optional<String> displayName() { return Optional.of(NAME); }
        @Override public OptionalInt maxAttempts() { return OptionalInt.empty(); }
        @Override public boolean isClientGone() { return false; }
    }

    private static final class NoStore implements ClientStore {
        @Override public List<RememberedClient> load() { return List.of(); }
        @Override public void save(List<RememberedClient> clients) { }
    }

    private final Queue<Runnable> commandQueue = new ArrayDeque<>();
    private final Queue<Runnable> scanQueue = new ArrayDeque<>();
    private final Executor commandExecutor = commandQueue::add;
    private final Executor scanExecutor = scanQueue::add;
    private final FakeCommands commands = new FakeCommands();
    private final List<String> clipboard = new ArrayList<>();
    private final Map<String, LiveClient> links = new HashMap<>();
    private final List<Connection> connections = new ArrayList<>();
    private final ConnectionHistory history = new ConnectionHistory();
    private CliContext ctx;
    private ClientRegistry registry;
    private HostSettings settings;
    private ScriptProfileStore profiles;
    private GroupStore groups;
    private LiveConnectionsModel model;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        registry = new ClientRegistry(new NoStore(), pipe -> Optional.ofNullable(links.get(pipe)), history,
                event -> { }, clock, Runnable::run);
        settings = HostSettings.open(tempDir.resolve("settings"));
        profiles = new ScriptProfileStore(tempDir.resolve("profiles"));
        ctx = mock(CliContext.class);
        when(ctx.getClientRegistry()).thenReturn(registry);
        when(ctx.getConnections()).thenAnswer(invocation -> List.copyOf(connections));
        when(ctx.getSettings()).thenReturn(settings);
        when(ctx.getProfileStore()).thenReturn(profiles);
        groups = new GroupStore(new GroupsFile(tempDir.resolve(GroupsFile.FILE_NAME)));
        when(ctx.getGroupStore()).thenReturn(groups);
        model = new LiveConnectionsModel(ctx, commands, commandExecutor, scanExecutor, clipboard::add, clock);
    }

    @AfterEach
    void closeSettings() {
        settings.close();
    }

    // ── Scanning and connecting ────────────────────────────────────────────

    @Test
    void scan_runsOffTheCallingThreadWithTheStoredPrefix_thenShowsWhatItFound() {
        settings.set(SettingKeys.PIPE_PREFIX, "Bwu");
        commands.scanReply = List.of(new FoundPipe(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN));

        model.scan();

        assertAll(
                () -> assertTrue(commands.scannedPrefixes.isEmpty(), "nothing touched a pipe on the caller"),
                () -> assertTrue(model.view().isScanning()));
        drain(scanQueue);
        ConnectionsView view = model.view();
        assertAll(
                () -> assertEquals(List.of("Bwu"), commands.scannedPrefixes),
                () -> assertFalse(view.isScanning()),
                () -> assertEquals(Optional.of("Found 1 pipe(s)."), view.scanMessage()),
                () -> assertEquals(List.of(FOUND_PIPE), view.found().stream().map(ConnectionRow::id).toList()));
    }

    @Test
    void scan_whileAScanIsRunning_doesNotStartAnother() {
        model.scan();
        model.scan();

        assertEquals(1, scanQueue.size());
    }

    @Test
    void aFoundPipeTheHostThenConnectsToLeavesTheFoundGroup() {
        commands.scanReply = List.of(new FoundPipe(PIPE, Optional.empty(), GameStatus.UNKNOWN));
        model.scan();
        drain(scanQueue);

        identify(PIPE);

        assertEquals(List.of(RowGroup.CONNECTED), model.view().rows().stream().map(ConnectionRow::group).toList());
    }

    @Test
    void connectAllFound_connectsEveryFoundPipeOnTheCommandExecutor() {
        commands.scanReply = List.of(new FoundPipe(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN),
                new FoundPipe(OTHER_PIPE, Optional.empty(), GameStatus.UNKNOWN));
        model.scan();
        drain(scanQueue);

        model.connectAllFound();

        assertTrue(commands.connected.isEmpty(), "connecting blocks, so not on the render thread");
        drain(commandQueue);
        assertEquals(List.of(FOUND_PIPE, OTHER_PIPE), commands.connected);
    }

    // ── Row actions ────────────────────────────────────────────────────────

    @Test
    void disconnect_stopsTheScriptsAndDropsThePipeOnTheCommandExecutor() {
        identify(PIPE);

        model.disconnect(onlyRow());

        verify(ctx, never()).disconnect(anyString(), anyBoolean());
        drain(commandQueue);
        verify(ctx).disconnect(PIPE, true);
    }

    @Test
    void forget_forgetsTheClientByItsKeyOnTheCommandExecutor() {
        identify(PIPE);
        publish(new ClientClosed(new ClientRef(KEY, PIPE), CloseCause.DISCONNECTED, NOW));
        connections.clear();

        model.forget(onlyRow());

        verify(ctx, never()).forget(any(ClientKey.class));
        drain(commandQueue);
        verify(ctx).forget(KEY);
    }

    @Test
    void anActionTheRowDoesNotOfferDoesNothing() {
        identify(PIPE);

        model.forget(onlyRow());
        model.connect(onlyRow());
        drain(commandQueue);

        assertAll(
                () -> verify(ctx, never()).forget(any(ClientKey.class)),
                () -> assertTrue(commands.connected.isEmpty()));
    }

    @Test
    void consoleTargetAndOutputFilterRouteTheConsole() {
        identify(PIPE);
        ConnectionRow row = onlyRow();

        model.setConsoleTarget(row);
        model.toggleOutputFilter(row);
        when(ctx.getMountedConnectionName()).thenReturn(PIPE);
        model.toggleOutputFilter(onlyRow());

        assertAll(
                () -> verify(ctx).setActive(PIPE),
                () -> verify(ctx).mount(PIPE),
                () -> verify(ctx).unmount());
    }

    @Test
    void theRowsShowTheConsoleRoutingTheContextReports() {
        identify(PIPE);
        when(ctx.getActiveConnectionName()).thenReturn(PIPE);
        when(ctx.getMountedConnectionName()).thenReturn(PIPE);

        ConnectionRow row = onlyRow();

        assertTrue(row.isConsoleTarget() && row.isOutputFilter());
    }

    @Test
    void copyUuid_copiesTheWholeUuid() {
        identify(PIPE);

        model.copyUuid(onlyRow());

        assertEquals(List.of(UUID), clipboard);
    }

    @Test
    void notResponding_countsTheClientsWhosePipeDropped_forTheSidebar() {
        identify(PIPE);
        int before = model.notResponding();

        publish(new ReconnectStateChanged(new ClientRef(KEY, PIPE),
                new ReconnectState.Reconnecting(NOW.toEpochMilli(), 1, RETRY_MS), NOW));

        assertEquals(0, before);
        assertEquals(1, model.notResponding());
        assertEquals(1, model.view().notResponding(), "the page and the sidebar agree");
    }

    // ── Header settings ────────────────────────────────────────────────────

    @Test
    void theHeaderReadsAndWritesTheConnectingSettings() {
        model.setAutoConnect(false);

        ConnectionsView view = model.view();
        assertAll(
                () -> assertFalse(settings.get(SettingKeys.AUTO_CONNECT)),
                () -> assertFalse(view.isAutoConnect()),
                () -> assertEquals("BotWithUs", view.pipePrefix()),
                () -> assertEquals(Duration.ofMillis(settings.get(SettingKeys.SCAN_INTERVAL_MS)),
                        view.scanInterval()));
    }

    @Test
    void setPipePrefix_storesAValidPrefixAndRefusesAnInvalidOneWithAReason() {
        Optional<String> refused = model.setPipePrefix("Bot With*Us");
        Optional<String> stored = model.setPipePrefix("BotWithUs_1");

        assertAll(
                () -> assertTrue(refused.isPresent()),
                () -> assertEquals(Optional.empty(), stored),
                () -> assertEquals("BotWithUs_1", settings.get(SettingKeys.PIPE_PREFIX)));
    }

    @Test
    void setPipePrefix_acceptsTheGlobTheFieldShows() {
        assertEquals(Optional.empty(), model.setPipePrefix("Bwu*"));
        assertEquals("Bwu", settings.get(SettingKeys.PIPE_PREFIX));
    }

    // ── Detail ─────────────────────────────────────────────────────────────

    @Test
    void detail_readsResumeAfterRestartAndWritesIt() {
        identify(PIPE);
        profiles.setAutoStart(UUID, false);

        ConnectionRow row = onlyRow();
        boolean before = model.detail(row).resumeAfterRestart().orElseThrow();
        model.setResumeAfterRestart(row, true);
        boolean shownAtOnce = model.detail(row).resumeAfterRestart().orElseThrow();
        drain(commandQueue);

        assertAll(
                () -> assertFalse(before),
                () -> assertTrue(shownAtOnce, "the switch does not wait for the file"),
                () -> assertTrue(profiles.isAutoStart(UUID)),
                () -> assertEquals(Optional.of(true), model.detail(row).resumeAfterRestart()));
    }

    @Test
    void detail_hasNoResumeSwitchForAClientKeyedByItsPipe() {
        links.put(PIPE, new FakeLink(NOW, GameStatus.UNKNOWN));
        connections.add(connection(PIPE));
        publish(new ClientOpened(new ClientRef(PIPE), NOW));

        ConnectionRow row = onlyRow();
        model.setResumeAfterRestart(row, true);

        assertEquals(Optional.empty(), model.detail(row).resumeAfterRestart());
    }

    @Test
    void detail_listsTheGroupsTheClientIsIn_andTheReconnectPolicyFromSettings() {
        identify(PIPE);
        groups.addMember(groups.create(GROUP, Optional.empty()).orElseThrow().id(), KEY);
        groups.create("Questers", Optional.empty());
        settings.set(SettingKeys.RECONNECT_MAX_ATTEMPTS, 3L);

        ConnectionDetail detail = model.detail(onlyRow());

        assertAll(
                () -> assertEquals(List.of(GROUP), detail.groups()),
                () -> assertEquals(ConnectionText.policy(new ReconnectPolicy(3, 500L, 2.0, 15_000L)),
                        detail.policy()));
    }

    @Test
    void detail_hasTheClientsHistoryNewestFirst() {
        identify(PIPE);

        List<TimelineEntry> history = model.detail(onlyRow()).history();

        assertEquals(List.of("Connected", "Pipe opened"), history.stream().map(TimelineEntry::lead).toList());
    }

    @Test
    void aConnectedRowAveragesItsRpcCallsAcrossMethods() {
        identify(PIPE);
        RpcMetrics metrics = connectionOn(PIPE).getRpc().getMetrics();
        metrics.recordCall("get_account_info", FAST_MS * NANOS_PER_MS, false);
        metrics.recordCall("get_current_world", SLOW_MS * NANOS_PER_MS, true);

        LinkStats stats = onlyRow().stats().orElseThrow();

        assertAll(
                () -> assertEquals(2L, stats.calls()),
                () -> assertEquals(1L, stats.errors()),
                () -> assertEquals((FAST_MS + SLOW_MS) / 2.0, stats.avgRpcMs(), 1e-9));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** A connection on {@code pipe} that has opened and been identified as the test account. */
    private void identify(String pipe) {
        links.put(pipe, new FakeLink(NOW.minusSeconds(60),
                new GameStatus(GameState.IN_GAME, OptionalInt.of(1), true)));
        connections.add(connection(pipe));
        publish(new ClientOpened(new ClientRef(pipe), NOW.minusSeconds(60)));
        publish(new ClientIdentified(new ClientRef(KEY, pipe), Optional.of(NAME), NOW.minusSeconds(59)));
    }

    /** As the host bus delivers an event: to the history and the registry. */
    private void publish(HostEvent event) {
        history.accept(event);
        registry.accept(event);
    }

    private static Connection connection(String pipe) {
        Connection conn = mock(Connection.class);
        RpcClient rpc = mock(RpcClient.class);
        RpcMetrics metrics = new RpcMetrics();
        when(rpc.getMetrics()).thenReturn(metrics);
        ScriptRuntime runtime = mock(ScriptRuntime.class);
        when(runtime.getRunners()).thenReturn(List.of());
        when(conn.getName()).thenReturn(pipe);
        when(conn.getRpc()).thenReturn(rpc);
        when(conn.getRuntime()).thenReturn(runtime);
        when(conn.isAlive()).thenReturn(true);
        return conn;
    }

    private Connection connectionOn(String pipe) {
        return connections.stream().filter(c -> c.getName().equals(pipe)).findFirst().orElseThrow();
    }

    private ConnectionRow onlyRow() {
        List<ConnectionRow> rows = model.view().rows();
        assertEquals(1, rows.size(), rows.toString());
        return rows.getFirst();
    }

    private static void drain(Queue<Runnable> queue) {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }
}
