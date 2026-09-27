package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.diag.StubGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.clients.JsonClientStore;
import com.botwithus.bot.cli.clients.LiveClient;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientKeys;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.GameEventBridge;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.events.RunnerEventBridge;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.groups.StartWhenBackDrain;
import com.botwithus.bot.cli.groups.StartWhenBackQueue;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.management.ManagementControl;
import com.botwithus.bot.cli.management.ManagementFile;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.OrchestratorAuditLog;
import com.botwithus.bot.cli.management.Scope;
import com.botwithus.bot.cli.management.ScopedClientOrchestrator;
import com.botwithus.bot.cli.management.ScopedClientProvider;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.scripts.AfterReload;
import com.botwithus.bot.cli.scripts.ManagementReload;
import com.botwithus.bot.cli.scripts.ReloadSummary;
import com.botwithus.bot.cli.scripts.ReloadTarget;
import com.botwithus.bot.cli.scripts.ScriptReloader;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.ReconnectPolicySettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.impl.ScriptContextChannel;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.ReconnectPolicy;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.core.runtime.ConnectionContext;
import com.botwithus.bot.core.runtime.LoadIssues;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.LocalScriptLoader;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptGate;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.cli.watch.ScriptWatchControl;
import com.botwithus.bot.core.impl.ManagementContextImpl;
import com.botwithus.bot.core.impl.SharedStateImpl;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ManagementLoadReport;
import com.botwithus.bot.core.runtime.ManagementScriptLoader;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.core.gameval.SqliteGamevalIndex;

import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CliContext {

    private static final Logger log = LoggerFactory.getLogger(CliContext.class);

    /** How long forgetting a client, or saving the clients at shutdown, waits for queued host events. */
    private static final Duration HOST_EVENT_FLUSH = Duration.ofSeconds(2);

    @FunctionalInterface
    public interface ImageDisplay {
        void display(BufferedImage image);
    }

/** Progress indicator that can be started and completed with an image or error. */
    public interface ProgressDisplay {
        /** Show an indeterminate progress bar with a label. Returns an opaque handle. */
        Object start(String label);
        /** Replace the progress bar with an inline image. */
        void completeWithImage(Object handle, BufferedImage image);
        /** Replace the progress bar with an error message. */
        void completeWithError(Object handle, String message);
    }

    private final LogBuffer logBuffer;
    private final LogCapture logCapture;
    private final ClientProviderImpl clientProvider = new ClientProviderImpl();
    // Read from the render thread every frame while the command thread, the pipe
    // scanner and reconnects mutate them: copy-on-write, insertion-ordered.
    private final OrderedSnapshotMap<String, Connection> connections = new OrderedSnapshotMap<>();
    /** Guards every change to the connection table together with the active name. */
    private final Object connectionLock = new Object();
    private volatile String activeConnectionName;
    private volatile String mountedConnectionName;
    private ImageDisplay imageDisplay;
    private ProgressDisplay progressDisplay;
    private StreamManager streamManager;
    private Consumer<ScriptRunner> configPanelOpener;
    private Consumer<Connection> onConnect;
    private volatile LoadReport lastLoadReport = LoadReport.EMPTY;
    /**
     * The failed-load list across load passes and both script folders. Kept
     * apart from the host bus on purpose: the bus reports each failure once as
     * it happens, this answers "what is broken now" at any later time.
     */
    private final LoadIssues loadIssues = new LoadIssues();
    /** One reload at a time: the watcher, a button and a command can all start one. */
    private final Object reloadLock = new Object();
    // Watches the directory the loader actually reads, not "scripts" under the
    // working directory: those differ as soon as -Dbotwithus.scripts.dir or the
    // ~/.botwithus fallback is in play, and a watch on the wrong one never fires.
    private final ScriptWatchControl watchControl = new ScriptWatchControl(
            LocalScriptLoader::scriptsDir, this::onScriptFoldersChanged, line -> out().println(line));
    private ScriptProfileStore profileStore;
    private AutoStartManager autoStartManager;
    private ClientManager clientManager;
    private ManagementScriptRuntime managementRuntime;
    private NXTCache nxtCache;
    private boolean nxtCacheInitAttempted;
    private GamevalIndex gamevals;
    private final GroupStore groupStore;
    private final StartWhenBackQueue startWhenBack;
    private final StartWhenBackDrain startWhenBackDrain;
    /** Runs saves and other work that must not hold up the thread that asked for it. */
    private final Executor background;
    // Volatile: connections and runtimes read it from their own threads.
    private volatile HostSettings settings;
    /** Host-level events; unlike a connection's bus it exists with no client connected. */
    private final HostEventBus hostEvents = new HostEventBus();
    private final ConnectionHistory connectionHistory = new ConnectionHistory();
    private final Clock clock = Clock.systemUTC();
    /** Keys and publishes every client event; see {@link ClientKeys}. */
    private final ClientKeys clientKeys = new ClientKeys(hostEvents, this::isPipeLive);
    private final RunnerEventBridge runnerEvents = new RunnerEventBridge(hostEvents, clientKeys, clock);
    private final GameEventBridge gameEvents = new GameEventBridge(clientKeys);
    private final ConnectionStatusTracker statusTracker = new ConnectionStatusTracker(
            task -> Thread.ofVirtual().name("connection-status").start(task),
            ConnectionStatusTracker.POLL_INTERVAL, this::onStatusRefreshed);
    private final ClientRegistry clientRegistry;
    /** Each management script's targets; read on first use. */
    private final ManagementTargets managementTargets;
    private final OrchestratorAuditLog orchestratorAudit = new OrchestratorAuditLog(hostEvents::publish, clock);
    private final ManagementControl managementControl;

    public CliContext(LogBuffer logBuffer, LogCapture logCapture) {
        this(logBuffer, logCapture, DEFAULT_GROUPS_FILE);
    }

    /**
     * @param groupsFile where groups persist; remembered clients and the
     *                   start-when-back queue persist beside it. Package-private
     *                   so tests can keep them out of the user's real home directory.
     */
    CliContext(LogBuffer logBuffer, LogCapture logCapture, Path groupsFile) {
        this(logBuffer, logCapture, groupsFile,
                task -> Thread.ofVirtual().name("host-background").start(task));
    }

    /**
     * @param background runs the writes of {@code clients.json} and the starts
     *                   queued for a client that is back. Package-private so a
     *                   test can run them in line and see their effects as soon
     *                   as the host events are delivered.
     */
    CliContext(LogBuffer logBuffer, LogCapture logCapture, Path groupsFile, Executor background) {
        this.logBuffer = logBuffer;
        this.logCapture = logCapture;
        this.background = background;
        this.clientManager = new ClientManager(this);
        this.clientRegistry = new ClientRegistry(
                new JsonClientStore(groupsFile.resolveSibling(JsonClientStore.FILE_NAME)),
                this::liveClient, connectionHistory, hostEvents::publish, clock, background);
        this.groupStore = new GroupStore(new GroupsFile(groupsFile));
        this.startWhenBack = new StartWhenBackQueue(groupsFile.resolveSibling(StartWhenBackQueue.FILE_NAME));
        this.startWhenBackDrain = new StartWhenBackDrain(startWhenBack, this::liveRuntime, this::loadScripts,
                background, line -> out().println(line));
        this.managementTargets = new ManagementTargets(
                new ManagementFile(groupsFile.resolveSibling(ManagementFile.FILE_NAME)), groupStore);
        this.managementControl = new ManagementControl(this::managementRuntimeOrInit, managementTargets,
                groupStore, clientManager);
        hostEvents.subscribe(connectionHistory);
        hostEvents.subscribe(clientRegistry);
    }

    /**
     * Lazy-init the process-wide NXTCache handle the first time a connection
     * is made. The same handle is shared across all GameAPIImpl instances —
     * sqlite is safe to read from one connection, and reopening it per
     * connection would waste startup time.
     *
     * <p>{@link NXTCache#openForHost(long)} resolves its own source — an
     * explicit {@code -Dnxtcache.path} / {@code -Dnxtcache.live} override, else
     * the cache the client running as {@code clientPid} names in its own
     * {@code preferences.cfg}, else a known install location, else live JS5 —
     * so a shipped install needs no flag. It logs which of those it took.
     * Returns {@code null} only when opening genuinely fails (no
     * {@code NXTCache.dll}, or live JS5 unreachable with no local cache), in
     * which case config-type lookups surface a clear error as before.</p>
     *
     * <p><b>The first connection's pid decides for the process.</b> This is
     * one-shot by design — the handle is shared across every connection, as it
     * was before — so a second client connected afterwards reads the first
     * one's cache. That is right for the ordinary case of several accounts on
     * one install and wrong for the rare one of a live and a beta client side
     * by side; it is a pre-existing property of the shared handle rather than
     * something the pid introduced.</p>
     *
     * @param clientPid the pid of the connection triggering this lazy init
     */
    private synchronized NXTCache getOrInitNxtCache(long clientPid) {
        if (nxtCacheInitAttempted) {
            return nxtCache;
        }
        nxtCacheInitAttempted = true;
        try {
            nxtCache = NXTCache.openForHost(clientPid);
        } catch (Throwable t) {
            log.warn("NXTCache failed to open, config-type lookups will throw: {}", t.getMessage());
        }
        return nxtCache;
    }

    /**
     * Lazy-init the process-wide gameval name index, sharing one handle across
     * every GameAPIImpl for the same reason {@link #getOrInitNxtCache(long)} does.
     * Never null and never throws: when no {@code gameval.sqlite} is deployed
     * (or it fails to open) this is {@link GamevalIndex#empty()}, whose lookups
     * all come back empty, so scripts degrade instead of crashing. No separate
     * "attempted" flag is needed — the empty index is itself a valid result.
     */
    private synchronized GamevalIndex getOrInitGamevals() {
        if (gamevals == null) {
            gamevals = SqliteGamevalIndex.openDefaultOrEmpty();
        }
        return gamevals;
    }

    /**
     * Release the shared gameval index. Called from the two shutdown paths
     * (the {@code exit} command and the GUI's close handler) — not from
     * {@link #disconnectAll(boolean)}, which is also a mid-session operation.
     * Clears the field, so a later connection simply reopens the index.
     */
    public synchronized void closeGamevals() {
        if (gamevals != null) {
            gamevals.close();
            gamevals = null;
        }
    }

    public void setStreamManager(StreamManager sm) { this.streamManager = sm; }
    public StreamManager getStreamManager() { return streamManager; }

    public void setProfileStore(ScriptProfileStore store) { this.profileStore = store; }
    public ScriptProfileStore getProfileStore() { return profileStore; }

    public void setAutoStartManager(AutoStartManager manager) { this.autoStartManager = manager; }
    public AutoStartManager getAutoStartManager() { return autoStartManager; }

    /**
     * The process's one {@link HostSettings}, set by the composition root before
     * any command or panel runs. {@code null} only in tests that never set it.
     */
    public void setSettings(HostSettings settings) {
        this.settings = settings;
        if (settings != null) {
            watchControl.bind(settings);
            settings.onChange(SettingKeys.RPC_TIMEOUT_MS, this::applyRpcTimeout);
        }
    }

    /** Gives every open connection's RPC client the new per-call timeout; calls already waiting keep theirs. */
    private void applyRpcTimeout(long timeoutMs) {
        for (Connection conn : connections.values()) {
            RpcClient rpc = conn.getRpc();
            if (rpc != null) {
                rpc.setTimeout(timeoutMs);
            }
        }
    }

    /** {@code defaultTimeout} now, or the RPC client's own default when no settings are set. */
    private OptionalLong rpcTimeoutMs() {
        HostSettings current = settings;
        return current != null ? OptionalLong.of(current.get(SettingKeys.RPC_TIMEOUT_MS)) : OptionalLong.empty();
    }

    /** {@code scripts.stallAfterMs} now, read on every watchdog sweep so a change applies at once. */
    private long stallAfterMs() {
        HostSettings current = settings;
        return current != null ? current.get(SettingKeys.STALL_AFTER_MS) : ScriptRuntime.DEFAULT_STALL_AFTER_MS;
    }
    public HostSettings getSettings() { return settings; }

    public ClientManager getClientManager() { return clientManager; }

    /** The host-wide event bus: client lifecycle, script lifecycle, load failures. */
    public HostEventBus getHostEvents() { return hostEvents; }

    /** Recent host events per client and host-wide, fed by {@link #getHostEvents()}. */
    public ConnectionHistory getConnectionHistory() { return connectionHistory; }

    /** Keeps every connection's account, game state and world current. */
    public ConnectionStatusTracker getStatusTracker() { return statusTracker; }

    /** Every client the host knows, live or remembered, one per account. */
    public ClientRegistry getClientRegistry() { return clientRegistry; }

    /** The key the client on {@code pipe} is known by now; the pipe's own key until it is identified. */
    public ClientKey clientKeyOf(String pipe) {
        return clientKeys.refFor(pipe).key();
    }

    /** Adds the clients remembered by an earlier run. Call once, at startup. */
    public void loadClients() {
        clientRegistry.load();
    }

    /**
     * Saves the remembered clients as they are now, after every host event
     * already published has been applied. Call at shutdown, off the render thread.
     */
    public void saveClients() {
        if (!hostEvents.flush(HOST_EVENT_FLUSH)) {
            log.warn("Host events were not all applied before saving the remembered clients");
        }
        clientRegistry.saveNow();
    }

    private boolean isPipeLive(String pipe) {
        Connection conn = connections.get(pipe);
        return conn != null && conn.isAlive();
    }

    private Optional<LiveClient> liveClient(String pipe) {
        return Optional.ofNullable(connections.get(pipe)).map(ConnectionClient::new);
    }

    /**
     * Settles the key of a connection whose account was just read, then treats a
     * client on a remembered account as back; see {@link #clientBack}. A
     * connection no longer in the table is past identifying.
     */
    private void onStatusRefreshed(Connection conn) {
        String pipe = conn.getName();
        if (connections.get(pipe) != conn) {
            return;
        }
        clientKeys.identify(pipe, conn.getAccountUuid(), conn.getDisplayName(), clock.instant());
        rememberedAccountOn(pipe).ifPresent(uuid -> clientBack(pipe, uuid));
    }

    /**
     * The client on {@code pipe} was read and is on account {@code uuid}: an
     * unresolved group member on that pipe becomes the account, and what is
     * queued to start on it starts. Both are checked in memory first, so a
     * routine re-read does no work.
     */
    private void clientBack(String pipe, String uuid) {
        if (groupStore.isUnresolved(pipe)) {
            background.execute(() -> groupStore.resolve(pipe, uuid));
        }
        startWhenBackDrain.clientBack(pipe, uuid);
    }

    /**
     * The account of the client on {@code pipe}, if it has been identified and
     * can be remembered: a group member or a queued start can only name such an
     * account. Empty for a pipe key and for a second client open on an account.
     */
    private Optional<String> rememberedAccountOn(String pipe) {
        ClientKey key = clientKeyOf(pipe);
        return key.isRemembered() ? key.accountUuid() : Optional.empty();
    }

    private Optional<ScriptRuntime> liveRuntime(String pipe) {
        Connection conn = connections.get(pipe);
        return conn != null && conn.isAlive() ? Optional.ofNullable(conn.getRuntime()) : Optional.empty();
    }

    public ManagementScriptRuntime getManagementRuntime() {
        return managementRuntime;
    }

    /**
     * Initialises the management script runtime. Call after the ClientManager
     * is ready (i.e. after construction). Uses a global MessageBus and
     * SharedState shared across all management scripts.
     */
    public void initManagementRuntime() {
        if (managementRuntime != null) {
            return;
        }
        var messageBus = new MessageBusImpl();
        var sharedState = new SharedStateImpl();
        var mgmtContext = new ManagementContextImpl(
                clientManager, clientProvider, messageBus, sharedState);
        managementRuntime = new ManagementScriptRuntime(mgmtContext, runnerEvents);
        managementRuntime.setStallThreshold(this::stallAfterMs);
        managementRuntime.setContextFactory(script -> scopedManagementContext(script, messageBus, sharedState));
    }

    /**
     * The context a management script runs with: an orchestrator and a client
     * provider limited to its targets, recording what it does.
     */
    private ManagementContext scopedManagementContext(String script, MessageBus messageBus,
                                                      SharedState sharedState) {
        Supplier<Scope> scope = () -> managementTargets.scopeOf(script);
        return new ManagementContextImpl(
                new ScopedClientOrchestrator(script, clientManager, scope, this::accountOfClient, orchestratorAudit),
                new ScopedClientProvider(clientProvider, scope, this::accountOfClient),
                messageBus, sharedState, () -> managementTargets.apiTargetsOf(script));
    }

    /**
     * The account of the client an orchestrator call names: a connection's
     * pipe, or the account UUID of a client that is not connected. Empty for a
     * client with no account.
     */
    private Optional<String> accountOfClient(String name) {
        if (connections.get(name) != null) {
            return clientKeyOf(name).accountUuid();
        }
        return AccountReply.identified(name);
    }

    private ManagementScriptRuntime managementRuntimeOrInit() {
        if (managementRuntime == null) {
            initManagementRuntime();
        }
        return managementRuntime;
    }

    /** What each management script manages, and whether it should run. */
    public ManagementTargets getManagementTargets() { return managementTargets; }

    /** The recent orchestrator calls of each management script. */
    public OrchestratorAuditLog getOrchestratorAudit() { return orchestratorAudit; }

    /** Start, stop and restart management scripts, and stop a group without its manager restarting it. */
    public ManagementControl getManagementControl() { return managementControl; }

    /**
     * Loads management scripts from {@code scripts/management/} and registers
     * them in the management runtime.
     */
    public List<ManagementScript> loadManagementScripts() {
        if (managementRuntime == null) {
            initManagementRuntime();
        }
        ManagementLoadReport report = ManagementScriptLoader.loadReport();
        loadIssues.record(ScriptFolder.MANAGEMENT, report.results());
        managementTargets.recordLoaded(report.results().stream().flatMap(r -> r.scriptName().stream()).toList());
        return report.scripts();
    }

    public void connect(String pipeName) {
        String resolvedName = pipeName != null ? pipeName : PipeClient.firstAvailableOrThrow();
        if (connections.containsKey(resolvedName)) {
            out().println("Already connected to '" + resolvedName + "'. Use 'use " + resolvedName + "' to switch.");
            return;
        }
        long pid = SharedRegion.parsePid(resolvedName).orElseThrow(() ->
                new IllegalStateException("Pipe '" + resolvedName + "' has no embedded pid"));
        try {
            OpenedConnection opened = openConnection(resolvedName, pid);
            publishConnection(opened.conn(), opened.client());
        } catch (Exception e) {
            out().println("Connection failed: " + e.getMessage());
        }
    }

    /** A connection built over a live pipe, with the client view scripts see of it. */
    private record OpenedConnection(Connection conn, ClientImpl client) {}

    private OpenedConnection openConnection(String name, long pid) {
        Instant connectedAt = Instant.now();
        PipeClient pipe = new PipeClient(name);
        RpcClient rpc = new RpcClient(pipe);
        rpc.setConnectionName(name);
        EventBusImpl eventBus = new EventBusImpl();

        // Pump owns the SHM mapping; we open it before constructing
        // GameAPIImpl so the entity facades (snapshot reads) can read from
        // the same region. ClientImpl borrows the same region.
        SharedRegionEventPump pump = new SharedRegionEventPump(pid, eventBus::publish);
        GameAPIImpl gameAPI = new GameAPIImpl(rpc, getOrInitNxtCache(pid),
                () -> new GameSnapshotImpl(pump.region().snapshot()),
                new StubGuard(),
                eventBus::publish,
                getOrInitGamevals());
        ScriptContextImpl context = new ScriptContextImpl(gameAPI, eventBus, new MessageBusImpl());

        rpc.start();

        ScriptContextChannel scriptCtxChannel = new ScriptContextChannel(rpc, name);
        ClientImpl client = new ClientImpl(name, gameAPI, eventBus, pipe::isOpen, pump.region());
        ScriptRuntime runtime = newRuntime(context, name, scriptCtxChannel, eventBus);
        wireScriptGate(runtime, rpc, gameAPI);

        Connection conn = new Connection(name, pipe, rpc, runtime, new ScriptManagerImpl(runtime), connectedAt);
        conn.setEventBus(eventBus);
        conn.setEventPump(pump);
        conn.setGameAPI(gameAPI);
        conn.setScriptContextChannel(scriptCtxChannel);
        armReconnect(conn);
        return new OpenedConnection(conn, client);
    }

    private static ScriptRuntime newRuntime(ScriptContextImpl context, String name,
                                            ScriptContextChannel channel, EventBusImpl eventBus) {
        ScriptRuntime runtime = new ScriptRuntime(context,
                ConnectionContext::set, ConnectionContext::clear, eventBus::publish);
        runtime.setConnectionName(name);
        runtime.setPublisherFactory(channel::publisherFor);
        return runtime;
    }

    /**
     * One gate per connection, shared by the runtime (which tags script threads
     * and revokes) and the RPC client (which enforces). Both sides must see the
     * same instance or revocation is a no-op.
     */
    private static void wireScriptGate(ScriptRuntime runtime, RpcClient rpc, GameAPIImpl gameAPI) {
        ScriptGate scriptGate = new ScriptGate();
        runtime.setScriptGate(scriptGate);
        rpc.setScriptGate(scriptGate);
        gameAPI.setScriptGate(scriptGate);
    }

    /**
     * Recovers transient pipe drops, recording each recovery on the connection.
     * The policy is read from the settings at the start of every recovery, so a
     * change applies to the next one.
     */
    private void armReconnect(Connection conn) {
        ReconnectController reconnect = new ReconnectController(conn.getRpc(), conn.getPipe(),
                conn.getName(), conn.getName(), this::reconnectPolicy,
                conn::onReconnectState,
                conn.getEventBus()::publish);
        conn.setReconnectController(reconnect);
        reconnect.arm();
    }

    /** The reconnect policy the settings describe now; the default when none are set. */
    private ReconnectPolicy reconnectPolicy() {
        HostSettings current = settings;
        return current != null ? ReconnectPolicySettings.read(current) : ReconnectPolicy.DEFAULT;
    }

    /**
     * Makes a built connection visible to the rest of the host. Two threads (the
     * pipe scanner and a command) can race to connect the same pipe; the loser
     * closes what it built rather than replacing the winner's connection.
     */
    private void publishConnection(Connection conn, ClientImpl client) {
        String name = conn.getName();
        if (!registerConnection(conn)) {
            conn.close();
            out().println("Already connected to '" + name + "'. Use 'use " + name + "' to switch.");
            return;
        }
        clientProvider.putClient(name, client);
        // Reads the account right away, whatever connected the pipe, so the
        // runtime is bound to the account UUID for a manual connect too.
        statusTracker.attach(conn);
        statusTracker.startPolling(this::getConnections);
        if (onConnect != null) {
            try {
                onConnect.accept(conn);
            } catch (RuntimeException e) {
                log.warn("onConnect hook threw for '{}': {}", name, e.getMessage());
            }
        }
        out().println("Connected to pipe: " + conn.getPipe().getPipePath());
        if (connections.values().size() > 1) {
            out().println("Active connection set to '" + name + "'.");
        }
    }

    /**
     * Adds a fully built connection to the table and makes it the active one.
     * Reports it opened on the host event bus, and from then on its script
     * lifecycle and its connection-level game events too.
     * Package-private: {@link #connect} can only build a connection over a live
     * pipe, so this is the seam the connection-table tests drive.
     *
     * @return {@code false} if a connection with the same name is already registered
     */
    boolean registerConnection(Connection conn) {
        synchronized (connectionLock) {
            if (!connections.putIfAbsent(conn.getName(), conn)) {
                return false;
            }
            activeConnectionName = conn.getName();
            // Published under the lock, so a close of this connection can only queue after it.
            clientKeys.opened(conn.getName(), clock.instant());
        }
        applySettings(conn);
        reportToHostEvents(conn);
        return true;
    }

    /** The host settings that shape a connection: its RPC timeout and its runtime's stall threshold. */
    private void applySettings(Connection conn) {
        RpcClient rpc = conn.getRpc();
        OptionalLong timeout = rpcTimeoutMs();
        if (rpc != null && timeout.isPresent()) {
            rpc.setTimeout(timeout.getAsLong());
        }
        ScriptRuntime runtime = conn.getRuntime();
        if (runtime != null) {
            runtime.setStallThreshold(this::stallAfterMs);
        }
    }

    /** Feeds the connection's script lifecycle and game-side signals into the host bus. */
    private void reportToHostEvents(Connection conn) {
        ScriptRuntime runtime = conn.getRuntime();
        if (runtime != null) {
            runtime.setRunnerListener(runnerEvents);
        }
        EventBusImpl bus = conn.getEventBus();
        if (bus != null) {
            gameEvents.attach(bus, conn.getName());
        }
    }

    /**
     * Removes {@code name} while it still maps to {@code conn}, so a stale caller
     * cannot drop a newer connection registered under the same pipe name. Moves the
     * active connection to the oldest remaining one if it was the one removed.
     */
    private boolean unregisterConnection(String name, Connection conn, CloseCause cause) {
        synchronized (connectionLock) {
            boolean removed = connections.remove(name, conn);
            reassignActiveIfGone();
            if (removed) {
                publishClosed(name, cause);
            }
            return removed;
        }
    }

    /**
     * As {@link #unregisterConnection(String, Connection, CloseCause)}, whatever
     * {@code name} maps to.
     */
    private Connection unregisterConnection(String name, CloseCause cause) {
        synchronized (connectionLock) {
            Connection removed = connections.remove(name);
            reassignActiveIfGone();
            if (removed != null) {
                publishClosed(name, cause);
            }
            return removed;
        }
    }

    private void publishClosed(String name, CloseCause cause) {
        Instant at = clock.instant();
        clientKeys.publish(name, client -> new ClientClosed(client, cause, at));
    }

    private void reassignActiveIfGone() {
        synchronized (connectionLock) {
            String active = activeConnectionName;
            if (active != null && !connections.containsKey(active)) {
                activeConnectionName = connections.firstKey().orElse(null);
            }
        }
    }

    public void disconnect(String name, boolean force) {
        String target = name != null ? name : activeConnectionName;
        Connection conn = target != null ? connections.get(target) : null;
        if (conn == null) {
            out().println(target == null ? "No active connection." : "Connection not found: " + target);
            return;
        }
        if (conn.hasRunningScripts() && !force) {
            out().println("Connection '" + target + "' has running scripts. Use 'disconnect --force' to stop them and disconnect.");
            return;
        }
        // Save auto-start state before disconnecting
        if (autoStartManager != null && conn.getAccountName() != null) {
            autoStartManager.saveState(conn);
        }
        if (target.equals(mountedConnectionName)) {
            unmount();
            out().println("Auto-unmounted — mounted connection was disconnected.");
        }
        boolean wasActive = target.equals(activeConnectionName);
        if (!unregisterConnection(target, conn, CloseCause.DISCONNECTED)) {
            out().println("Connection not found: " + target);
            return;
        }
        clientProvider.removeClient(target);
        conn.close();
        out().println("Disconnected from '" + target + "'.");
        String active = activeConnectionName;
        if (wasActive && active != null) {
            out().println("Active connection switched to '" + active + "'.");
        }
    }

    public void disconnect(String name) {
        disconnect(name, false);
    }

    public void disconnectAll(boolean force) {
        if (streamManager != null) {
            streamManager.stopAll(connections::get);
        }
        for (Connection conn : connections.values()) {
            if (conn.hasRunningScripts() && !force) {
                out().println("Skipping '" + conn.getName() + "' — has running scripts. Use --force to override.");
                continue;
            }
            if (!unregisterConnection(conn.getName(), conn, CloseCause.DISCONNECTED)) {
                continue;
            }
            conn.close();
            clientProvider.removeClient(conn.getName());
            out().println("Disconnected from '" + conn.getName() + "'.");
        }
    }

    public void disconnectAll() {
        disconnectAll(false);
    }

    public boolean setActive(String name) {
        synchronized (connectionLock) {
            if (!connections.containsKey(name)) {
                return false;
            }
            activeConnectionName = name;
            return true;
        }
    }

    public List<BotScript> loadScripts() {
        return loadScriptReport().scripts();
    }

    /**
     * Loads scripts and returns the full {@link LoadReport} including per-JAR
     * failures. As a side effect, publishes a {@link ScriptLoadFailedEvent}
     * onto every active connection's event bus for each failure so the
     * notification overlay and Scripts panel can surface it, and a
     * {@link ScriptLoadFailed} onto the host event bus, which records it even
     * with no client connected.
     */
    public LoadReport loadScriptReport() {
        return recordLoadReport(SDNScriptLoader.loadLocalReport());
    }

    /**
     * Keeps {@code report} as the latest and publishes its failures. Package-private
     * so tests can report a failure without loading JARs from disk.
     */
    LoadReport recordLoadReport(LoadReport report) {
        this.lastLoadReport = report;
        loadIssues.record(ScriptFolder.SCRIPTS, report.results());
        for (ScriptLoadResult failure : report.failures()) {
            Throwable cause = failure.error().orElse(new IllegalStateException("unknown"));
            broadcastEvent(new ScriptLoadFailedEvent(failure.jar(), cause));
            // The broadcast reaches connected clients only; the host bus keeps it
            // when none is connected.
            hostEvents.publish(new ScriptLoadFailed(failure.jar(), cause, clock.instant()));
        }
        return report;
    }

    /** Snapshot of the most recent {@link #loadScriptReport()} call. Never null. */
    public LoadReport getLastLoadReport() {
        return lastLoadReport;
    }

    /**
     * The failed-load list: every JAR in either script folder whose last load
     * failed, until it loads cleanly or is deleted, plus duplicate-name
     * warnings from the latest pass. Fed by every load pass.
     */
    public LoadIssues getLoadIssues() {
        return loadIssues;
    }

    private void broadcastEvent(GameEvent event) {
        for (Connection conn : connections.values()) {
            EventBusImpl bus = conn.getEventBus();
            if (bus != null) {
                bus.publish(event);
            }
        }
    }

    /**
     * Stub: blueprint loading was removed in slice 3 (the blueprint
     * subsystem depended on the legacy RPC-shaped read surface). Returns
     * an empty list so callers don't need a guard.
     */
    public List<BotScript> loadBlueprints() {
        return List.of();
    }

    /**
     * Called when a connection error is detected (pipe closed, RPC failure, etc.).
     * Removes the dead connection, stops its scripts, and switches to the next
     * available connection (or clears the active view).
     */
    public void handleConnectionError(String connName) {
        if (streamManager != null) {
            streamManager.handleConnectionLost(connName);
        }
        if (connName.equals(mountedConnectionName)) {
            unmount();
            out().println("Auto-unmounted — mounted connection was lost.");
        }
        boolean wasActive = connName.equals(activeConnectionName);
        Connection conn = unregisterConnection(connName, CloseCause.CONNECTION_LOST);
        clientProvider.removeClient(connName);
        if (conn != null) {
            conn.close();
            out().println("Connection '" + connName + "' lost — removed.");
        }
        String active = activeConnectionName;
        if (wasActive && active != null) {
            out().println("Active connection switched to '" + active + "'.");
        }
    }

    /**
     * Forgets a client that has gone: removes its connection if one is still
     * registered, drops it from the client registry (and from the remembered
     * clients, if it was one) and its history, and publishes {@link ClientForgotten}.
     * Refuses a client whose pipe is still open. Blocks while a registered
     * connection stops its scripts, so call it off the render thread.
     *
     * @param key the client's key; see {@link #clientKeyOf}
     */
    public ForgetResult forget(ClientKey key) {
        // Applies what is already published first, so a client that closed a
        // moment ago is found as closed.
        if (!hostEvents.flush(HOST_EVENT_FLUSH)) {
            log.warn("Host events were not all applied before forgetting {}", key);
        }
        List<Connection> registered = clientKeys.pipesOf(key).stream()
                .map(connections::get)
                .filter(Objects::nonNull)
                .toList();
        if (registered.stream().anyMatch(Connection::isAlive)) {
            return ForgetResult.STILL_CONNECTED;
        }
        if (registered.isEmpty() && !clientRegistry.contains(key)
                && !connectionHistory.clients().contains(key)) {
            return ForgetResult.NOT_FOUND;
        }
        registered.forEach(conn -> handleConnectionError(conn.getName()));
        // Published after the close, so every subscriber drops the client after it.
        clientKeys.forget(key, clock.instant());
        return ForgetResult.FORGOTTEN;
    }

    /** As {@link #forget(ClientKey)}, for the client the host last knew on {@code pipe}. */
    public ForgetResult forget(String pipe) {
        return forget(clientKeyOf(pipe));
    }

    public boolean hasConnections() { return !connections.isEmpty(); }
    public boolean hasActiveConnection() { return activeConnectionName != null; }
    public String getActiveConnectionName() { return activeConnectionName; }

    public Connection getActiveConnection() {
        String active = activeConnectionName;
        return active != null ? connections.get(active) : null;
    }

    /**
     * The connected clients in the order they connected. An immutable snapshot:
     * safe to iterate from any thread, including the render thread, while other
     * threads connect and disconnect. Call again to see later changes.
     */
    public List<Connection> getConnections() { return connections.values(); }

    public ScriptRuntime getRuntime() {
        Connection conn = getActiveConnection();
        return conn != null ? conn.getRuntime() : null;
    }

    public LogBuffer getLogBuffer() { return logBuffer; }
    public PrintStream out() { return logCapture.getOriginalOut(); }
    public PrintStream err() { return logCapture.getOriginalErr(); }

    public void setImageDisplay(ImageDisplay d) { this.imageDisplay = d; }
    public ImageDisplay getImageDisplay() { return imageDisplay; }

    public void setProgressDisplay(ProgressDisplay d) { this.progressDisplay = d; }
    public ProgressDisplay getProgressDisplay() { return progressDisplay; }

    /**
     * Wiring hook for an observer that wants to react to a successful
     * {@link #connect}, e.g. the notification overlay subscribing to the
     * new connection's event bus.
     */
    public void setOnConnect(Consumer<Connection> hook) { this.onConnect = hook; }

    public void setConfigPanelOpener(Consumer<ScriptRunner> opener) { this.configPanelOpener = opener; }
    public void openConfigPanel(ScriptRunner runner) {
        if (configPanelOpener != null) {
            configPanelOpener.accept(runner);
        }
    }

    // --- Groups and the start-when-back queue ---

    private static final Path DEFAULT_GROUPS_FILE =
            Path.of(System.getProperty("user.home"), ".botwithus", GroupsFile.FILE_NAME);

    /**
     * Loads the groups and the start-when-back queue saved by an earlier run.
     * Call once, at startup. A groups file from a host that kept members by pipe
     * name is migrated: a member whose pipe has an identified client now becomes
     * that client's account, and the rest stay on their group as unresolved
     * until a client on that pipe is identified or the user removes them.
     */
    public void loadGroups() {
        groupStore.load(pipe -> isPipeLive(pipe) ? rememberedAccountOn(pipe) : Optional.empty());
        startWhenBack.load();
    }

    /** The host's groups: create, rename, change members and managers, look up. */
    public GroupStore getGroupStore() { return groupStore; }

    /** Scripts waiting to start on clients that are not connected. */
    public StartWhenBackQueue getStartWhenBackQueue() { return startWhenBack; }

    /** The group called exactly {@code name}. */
    public Optional<ClientGroup> findGroup(String name) {
        return groupStore.byName(name);
    }

    /** The live connections of the group called {@code groupName}; see {@link #getGroupConnections(ClientGroup)}. */
    public List<Connection> getGroupConnections(String groupName) {
        return groupStore.byName(groupName).map(this::getGroupConnections).orElse(List.of());
    }

    /** The live connections of the group with id {@code id}; see {@link #getGroupConnections(ClientGroup)}. */
    public List<Connection> getGroupConnections(GroupId id) {
        return groupStore.get(id).map(this::getGroupConnections).orElse(List.of());
    }

    /**
     * The live connections of {@code group}'s members, in member order. A member
     * that is not connected now is skipped.
     */
    public List<Connection> getGroupConnections(ClientGroup group) {
        return group.members().stream()
                .flatMap(uuid -> liveConnectionsOf(uuid).stream())
                .toList();
    }

    /**
     * The live connection of the client on account {@code accountUuid}, as a
     * list: empty while it is not connected. A second client open on the same
     * account at once is a different client and is not included.
     */
    public List<Connection> liveConnectionsOf(String accountUuid) {
        if (AccountReply.identified(accountUuid).isEmpty()) {
            return List.of();
        }
        return clientKeys.pipesOf(ClientKey.account(accountUuid)).stream()
                .map(connections::get)
                .filter(Objects::nonNull)
                .filter(Connection::isAlive)
                .toList();
    }

    /**
     * Queues {@code script} to start on the client on account {@code accountUuid}
     * when it is back. If that client is connected already, it is started now,
     * on a background thread, rather than waiting for it to come back.
     *
     * @return {@code false} if that start was already queued
     * @throws IllegalArgumentException if {@code accountUuid} is not an account
     *                                  UUID or {@code script} is blank
     */
    public boolean startWhenBack(String accountUuid, String script) {
        boolean isQueued = startWhenBack.enqueue(accountUuid, script, clock.instant());
        for (Connection conn : liveConnectionsOf(accountUuid)) {
            startWhenBackDrain.clientBack(conn.getName(), accountUuid);
        }
        return isQueued;
    }

    /** "Name (uuid)" when the host knows the account's name, else the UUID alone. */
    public String describeAccount(String accountUuid) {
        return AccountReply.identified(accountUuid)
                .flatMap(uuid -> clientRegistry.get(ClientKey.account(uuid)))
                .flatMap(ClientRecord::name)
                .map(name -> name + " (" + accountUuid + ")")
                .orElse(accountUuid);
    }

    public void mount(String connectionName) {
        this.mountedConnectionName = connectionName;
        logCapture.setConnectionFilter(name -> name.equals(connectionName));
    }

    public void unmount() {
        this.mountedConnectionName = null;
        logCapture.setConnectionFilter(null);
    }

    public boolean isMounted() { return mountedConnectionName != null; }
    public String getMountedConnectionName() { return mountedConnectionName; }

    /**
     * What a plain reload starts afterwards: the scripts that were running,
     * when {@link SettingKeys#RESTART_AFTER_RELOAD} is on, else nothing.
     */
    public AfterReload afterReloadSetting() {
        HostSettings current = settings;
        return AfterReload.fromSetting(current != null && current.get(SettingKeys.RESTART_AFTER_RELOAD));
    }

    /**
     * Reloads {@code scripts/} on {@code connections}: notes what each is
     * running, stops them all, registers a fresh set of scripts on each, then
     * starts what {@code after} says. See {@link ScriptReloader}.
     */
    public ReloadSummary reloadScripts(List<Connection> connections, AfterReload after) {
        List<ReloadTarget> targets = connections.stream()
                .map(conn -> new ReloadTarget(conn.getName(), conn.getRuntime()))
                .toList();
        synchronized (reloadLock) {
            return newReloader().reload(targets, after);
        }
    }

    /** {@link #reloadScripts} on every live connection; with none, still refreshes the load report. */
    public ReloadSummary reloadAllScripts(AfterReload after) {
        return reloadScripts(getConnections().stream().filter(Connection::isAlive).toList(), after);
    }

    /** Reloads {@code scripts/management/} into the management runtime. */
    public ManagementReload reloadManagementScripts(AfterReload after) {
        if (managementRuntime == null) {
            initManagementRuntime();
        }
        synchronized (reloadLock) {
            return newReloader().reloadManagement(managementRuntime, after);
        }
    }

    /** Built per call so each load pass goes through this object's own load methods. */
    private ScriptReloader newReloader() {
        return new ScriptReloader(this::loadScripts, this::loadManagementScripts);
    }

    /**
     * Starts watching the scripts folders and turns {@link SettingKeys#AUTO_RELOAD}
     * on, so the choice persists and the Settings page agrees.
     */
    public void startScriptWatcher() {
        HostSettings current = settings;
        if (current != null) {
            current.set(SettingKeys.AUTO_RELOAD, Boolean.TRUE);
        }
        // Also directly: the setting may already be on while the watch is not
        // running (it failed to start), and then setting it changes nothing.
        watchControl.start();
    }

    /** Stops watching and turns {@link SettingKeys#AUTO_RELOAD} off. */
    public void stopScriptWatcher() {
        HostSettings current = settings;
        if (current != null) {
            current.set(SettingKeys.AUTO_RELOAD, Boolean.FALSE);
        }
        watchControl.stop();
    }

    /** Whether the watch is actually running, which the setting alone does not prove. */
    public boolean isWatcherRunning() {
        return watchControl.isRunning();
    }

    private void onScriptFoldersChanged(Set<ScriptFolder> folders) {
        AfterReload after = afterReloadSetting();
        if (folders.contains(ScriptFolder.SCRIPTS)) {
            out().println("[ScriptWatcher] Script files changed — reloading...");
            ReloadSummary summary = reloadAllScripts(after);
            summary.clients().forEach(c -> out().println(
                    "[ScriptWatcher] Reloaded " + c.loaded() + " script(s) on " + c.connection()));
            summary.restarted().forEach(p -> out().println(
                    "[ScriptWatcher] Restarted " + p.script() + " on " + p.connection()));
            summary.missingLines().forEach(line -> out().println("[ScriptWatcher] " + line));
        }
        if (folders.contains(ScriptFolder.MANAGEMENT)) {
            out().println("[ScriptWatcher] Management scripts changed — reloading...");
            ManagementReload summary = reloadManagementScripts(after);
            out().println("[ScriptWatcher] Reloaded " + summary.loaded() + " management script(s)");
            summary.missing().forEach(name -> out().println(
                    "[ScriptWatcher] Not restarted: " + name + " is no longer in the management folder."));
        }
    }

}
