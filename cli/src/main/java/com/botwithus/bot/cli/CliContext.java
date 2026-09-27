package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.diag.StubGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.GameEventBridge;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.events.RunnerEventBridge;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
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
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.LocalScriptLoader;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptGate;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.cli.watch.ScriptWatcher;
import com.botwithus.bot.core.impl.ManagementContextImpl;
import com.botwithus.bot.core.impl.SharedStateImpl;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ManagementScriptLoader;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.core.gameval.SqliteGamevalIndex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class CliContext {

    private static final Logger log = LoggerFactory.getLogger(CliContext.class);

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
    private final OrderedSnapshotMap<String, ConnectionGroup> groups = new OrderedSnapshotMap<>();
    /** Guards every change to the connection table together with the active name. */
    private final Object connectionLock = new Object();
    /** Serialises group-file writes, so the file always holds a complete, recent state. */
    private final Object groupsFileLock = new Object();
    private volatile String activeConnectionName;
    private volatile String mountedConnectionName;
    private ImageDisplay imageDisplay;
    private ProgressDisplay progressDisplay;
    private StreamManager streamManager;
    private Consumer<ScriptRunner> configPanelOpener;
    private Consumer<Connection> onConnect;
    private volatile LoadReport lastLoadReport = LoadReport.EMPTY;
    private ScriptWatcher scriptWatcher;
    private ScriptProfileStore profileStore;
    private AutoStartManager autoStartManager;
    private ClientManager clientManager;
    private ManagementScriptRuntime managementRuntime;
    private NXTCache nxtCache;
    private boolean nxtCacheInitAttempted;
    private GamevalIndex gamevals;
    private final Path groupsFile;
    private HostSettings settings;
    /** Host-level events; unlike a connection's bus it exists with no client connected. */
    private final HostEventBus hostEvents = new HostEventBus();
    private final ConnectionHistory connectionHistory = new ConnectionHistory();
    private final Clock clock = Clock.systemUTC();
    private final RunnerEventBridge runnerEvents = new RunnerEventBridge(hostEvents, clock);
    private final GameEventBridge gameEvents = new GameEventBridge(hostEvents);
    private final ConnectionStatusTracker statusTracker = new ConnectionStatusTracker(
            task -> Thread.ofVirtual().name("connection-status").start(task),
            ConnectionStatusTracker.POLL_INTERVAL);

    public CliContext(LogBuffer logBuffer, LogCapture logCapture) {
        this(logBuffer, logCapture, DEFAULT_GROUPS_FILE);
    }

    /**
     * @param groupsFile where groups persist. Package-private so tests can keep
     *                   their groups out of the user's real home directory.
     */
    CliContext(LogBuffer logBuffer, LogCapture logCapture, Path groupsFile) {
        this.logBuffer = logBuffer;
        this.logCapture = logCapture;
        this.groupsFile = groupsFile;
        this.clientManager = new ClientManager(this);
        hostEvents.subscribe(connectionHistory);
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
    public void setSettings(HostSettings settings) { this.settings = settings; }
    public HostSettings getSettings() { return settings; }

    public ClientManager getClientManager() { return clientManager; }

    /** The host-wide event bus: client lifecycle, script lifecycle, load failures. */
    public HostEventBus getHostEvents() { return hostEvents; }

    /** Recent host events per client and host-wide, fed by {@link #getHostEvents()}. */
    public ConnectionHistory getConnectionHistory() { return connectionHistory; }

    /** Keeps every connection's account, game state and world current. */
    public ConnectionStatusTracker getStatusTracker() { return statusTracker; }

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
    }

    /**
     * Loads management scripts from {@code scripts/management/} and registers
     * them in the management runtime.
     */
    public List<ManagementScript> loadManagementScripts() {
        if (managementRuntime == null) {
            initManagementRuntime();
        }
        return ManagementScriptLoader.loadScripts();
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

    /** Recovers transient pipe drops, recording each recovery on the connection. */
    private static void armReconnect(Connection conn) {
        ReconnectController reconnect = new ReconnectController(conn.getRpc(), conn.getPipe(),
                conn.getName(), conn.getName(), ReconnectPolicy.DEFAULT,
                conn::onReconnectState,
                conn.getEventBus()::publish);
        conn.setReconnectController(reconnect);
        reconnect.arm();
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
            hostEvents.publish(new ClientOpened(new ClientRef(conn.getName()), clock.instant()));
        }
        reportToHostEvents(conn);
        return true;
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
        hostEvents.publish(new ClientClosed(new ClientRef(name), cause, clock.instant()));
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

    // --- Connection Group management & persistence ---

    private static final Path DEFAULT_GROUPS_FILE = Path.of(System.getProperty("user.home"), ".botwithus", "groups.json");

    /** Simple DTO for JSON serialization of a group. */
    private static class GroupData {
        String description;
        List<String> members;
        GroupData() {}
        GroupData(String description, List<String> members) {
            this.description = description;
            this.members = members;
        }
    }

    /** Loads persisted groups from ~/.botwithus/groups.json. */
    public void loadGroups() {
        if (!Files.exists(groupsFile)) {
            return;
        }
        try {
            String json = Files.readString(groupsFile);
            Gson gson = new Gson();
            Map<String, GroupData> data = gson.fromJson(json,
                    new TypeToken<LinkedHashMap<String, GroupData>>() {}.getType());
            if (data != null) {
                Map<String, ConnectionGroup> loaded = new LinkedHashMap<>();
                for (var entry : data.entrySet()) {
                    ConnectionGroup group = new ConnectionGroup(entry.getKey());
                    GroupData gd = entry.getValue();
                    if (gd.description != null) {
                        group.setDescription(gd.description);
                    }
                    if (gd.members != null) {
                        gd.members.forEach(group::add);
                    }
                    loaded.put(entry.getKey(), group);
                }
                groups.replaceAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load groups", e);
        }
    }

    /** Persists current groups to ~/.botwithus/groups.json. */
    void saveGroups() {
        // The snapshot is taken inside the lock, so whichever save runs last
        // writes a state that includes every change made before it.
        synchronized (groupsFileLock) {
            try {
                Files.createDirectories(groupsFile.getParent());
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                Map<String, GroupData> data = new LinkedHashMap<>();
                for (var entry : groups.asMap().entrySet()) {
                    ConnectionGroup g = entry.getValue();
                    data.put(entry.getKey(), new GroupData(g.getDescription(), new ArrayList<>(g.getConnectionNames())));
                }
                Files.writeString(groupsFile, gson.toJson(data));
            } catch (Exception e) {
                log.error("Failed to save groups", e);
            }
        }
    }

    public void createGroup(String name) {
        groups.put(name, new ConnectionGroup(name));
        saveGroups();
    }

    public boolean deleteGroup(String name) {
        boolean removed = groups.remove(name) != null;
        if (removed) {
            saveGroups();
        }
        return removed;
    }

    public ConnectionGroup getGroup(String name) {
        return groups.get(name);
    }

    /**
     * The groups in creation order. An immutable snapshot, safe to iterate from any
     * thread; call again to see later changes. The groups themselves are live.
     */
    public Map<String, ConnectionGroup> getGroups() {
        return groups.asMap();
    }

    public void addToGroup(String groupName, String connectionName) {
        ConnectionGroup group = groups.get(groupName);
        if (group != null) {
            group.add(connectionName);
            saveGroups();
        }
    }

    public void removeFromGroup(String groupName, String connectionName) {
        ConnectionGroup group = groups.get(groupName);
        if (group != null) {
            group.remove(connectionName);
            saveGroups();
        }
    }

    /**
     * Returns the list of active (connected) Connection objects for a group.
     * Connections that are in the group but not currently connected are skipped.
     */
    public List<Connection> getGroupConnections(String groupName) {
        ConnectionGroup group = groups.get(groupName);
        if (group == null) {
            return List.of();
        }
        List<Connection> result = new ArrayList<>();
        for (String connName : group.getConnectionNames()) {
            Connection conn = connections.get(connName);
            if (conn != null && conn.isAlive()) {
                result.add(conn);
            }
        }
        return result;
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

    public void startScriptWatcher() {
        if (scriptWatcher != null && scriptWatcher.isRunning()) {
            return;
        }
        // The directory the loader actually reads — not "scripts" relative to
        // the working directory, which is a different place as soon as the
        // -Dbotwithus.scripts.dir override or the ~/.botwithus fallback is in
        // play. A watcher on the wrong directory never fires.
        Path scriptsDir = LocalScriptLoader.scriptsDir();
        if (!Files.isDirectory(scriptsDir)) {
            out().println("Script watcher not started: " + scriptsDir.toAbsolutePath()
                    + " does not exist.");
            return;
        }
        scriptWatcher = new ScriptWatcher(scriptsDir, () -> {
            out().println("[ScriptWatcher] Script files changed — reloading...");
            for (Connection conn : connections.values()) {
                if (conn.isAlive()) {
                    conn.getRuntime().stopAll();
                    List<BotScript> scripts = loadScripts();
                    for (BotScript script : scripts) {
                        conn.getRuntime().registerScript(script);
                    }
                    out().println("[ScriptWatcher] Reloaded " + scripts.size() + " script(s) on " + conn.getName());
                }
            }
        });
        scriptWatcher.start();
        out().println("Script file watcher started.");
    }

    public void stopScriptWatcher() {
        if (scriptWatcher != null) {
            scriptWatcher.stop();
            scriptWatcher = null;
            out().println("Script file watcher stopped.");
        }
    }

    public boolean isWatcherRunning() {
        return scriptWatcher != null && scriptWatcher.isRunning();
    }

}
