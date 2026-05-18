package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.diag.StubGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
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
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.cli.watch.ScriptWatcher;
import com.botwithus.bot.api.launcher.GameLauncher;
import com.botwithus.bot.core.loader.BwuClient;
import com.botwithus.bot.core.loader.BwuGameLauncher;
import com.botwithus.bot.core.impl.ManagementContextImpl;
import com.botwithus.bot.core.impl.SharedStateImpl;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ManagementScriptLoader;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.cache.NXTCache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, ConnectionGroup> groups = new LinkedHashMap<>();
    private String activeConnectionName;
    private String mountedConnectionName;
    private ImageDisplay imageDisplay;
    private ProgressDisplay progressDisplay;
    private StreamManager streamManager;
    private Consumer<ScriptRunner> configPanelOpener;
    private Consumer<Connection> onConnect;
    private LoadReport lastLoadReport = LoadReport.EMPTY;
    private ScriptWatcher scriptWatcher;
    private ScriptProfileStore profileStore;
    private AutoStartManager autoStartManager;
    private ClientManager clientManager;
    private ManagementScriptRuntime managementRuntime;
    private NXTCache nxtCache;
    private boolean nxtCacheInitAttempted;

    public CliContext(LogBuffer logBuffer, LogCapture logCapture) {
        this.logBuffer = logBuffer;
        this.logCapture = logCapture;
        this.clientManager = new ClientManager(this);
    }

    /**
     * Lazy-init the process-wide NXTCache handle the first time a connection
     * is made. The same handle is shared across all GameAPIImpl instances —
     * sqlite is safe to read from one connection, and reopening it per
     * connection would waste startup time. Returns {@code null} when the
     * cache isn't configured ({@code -Dnxtcache.path} unset) or fails to
     * open; callers (i.e. config-type lookups) will surface a clear error.
     */
    private synchronized NXTCache getOrInitNxtCache() {
        if (nxtCacheInitAttempted) {
            return nxtCache;
        }
        nxtCacheInitAttempted = true;
        try {
            nxtCache = NXTCache.tryOpenFromSystemProperty();
            if (nxtCache != null) {
                log.info("NXTCache opened (config-type lookups now cache-backed)");
            } else {
                log.debug("NXTCache not configured — set -Dnxtcache.path=<dir> to enable config-type lookups");
            }
        } catch (Throwable t) {
            log.warn("NXTCache failed to open: {}", t.getMessage());
        }
        return nxtCache;
    }

    public void setStreamManager(StreamManager sm) { this.streamManager = sm; }
    public StreamManager getStreamManager() { return streamManager; }

    public void setProfileStore(ScriptProfileStore store) { this.profileStore = store; }
    public ScriptProfileStore getProfileStore() { return profileStore; }

    public void setAutoStartManager(AutoStartManager manager) { this.autoStartManager = manager; }
    public AutoStartManager getAutoStartManager() { return autoStartManager; }

    public ClientManager getClientManager() { return clientManager; }

    public ManagementScriptRuntime getManagementRuntime() {
        return managementRuntime;
    }

    /**
     * Initialises the management script runtime. Call after the ClientManager
     * is ready (i.e. after construction). Uses a global MessageBus and
     * SharedState shared across all management scripts.
     */
    public void initManagementRuntime() {
        initManagementRuntime(null);
    }

    /**
     * Initialises the management script runtime with an optional pre-loaded
     * BwuClient. If {@code existingClient} is non-null it is reused instead
     * of loading a second copy of bwu.dll (which would conflict).
     */
    public void initManagementRuntime(BwuClient existingClient) {
        if (managementRuntime != null) {
            return;
        }
        var messageBus = new MessageBusImpl();
        var sharedState = new SharedStateImpl();

        GameLauncher launcher = null;
        if (existingClient == null) {
            // Optionally load the native game launcher (bwu.dll)
            // 1) Check filesystem (covers jlink/prod where DLL sits next to the executable)
            // 2) Fall back to extracting from bundled resources (covers Gradle dev runs)
            Path dllPath = resolveBwuDll();
            if (dllPath == null) {
                log.debug("Game launcher (bwu.dll) not available — management scripts will not have launcher access");
            } else {
                var bwuClient = BwuClient.load(dllPath);
                if (bwuClient.isPresent()) {
                    launcher = new BwuGameLauncher(bwuClient.get());
                    log.info("Game launcher (bwu.dll) loaded from {}", dllPath);
                } else {
                    log.debug("Game launcher (bwu.dll) found but failed to load from {}", dllPath);
                }
            }
        } else {
            launcher = new BwuGameLauncher(existingClient);
            log.info("Game launcher reusing existing BwuClient instance");
        }

        var mgmtContext = new ManagementContextImpl(
                clientManager, clientProvider, messageBus, sharedState, launcher);
        managementRuntime = new ManagementScriptRuntime(mgmtContext);
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
            PipeClient pipe = new PipeClient(resolvedName);
            RpcClient rpc = new RpcClient(pipe);
            rpc.setConnectionName(resolvedName);
            EventBusImpl eventBus = new EventBusImpl();
            MessageBusImpl messageBus = new MessageBusImpl();

            // Pump owns the SHM mapping; we open it before constructing
            // GameAPIImpl so both the component cache (per-iface invalidation
            // tokens) and the entity facades (snapshot reads) can read from
            // the same region. ClientImpl borrows the same region.
            SharedRegionEventPump pump = new SharedRegionEventPump(pid, eventBus::publish);
            GameAPIImpl gameAPI = new GameAPIImpl(rpc, getOrInitNxtCache(),
                    iface -> pump.region().snapshot().ifaceVersion(iface),
                    () -> new GameSnapshotImpl(pump.region().snapshot()),
                    new StubGuard());
            ScriptContextImpl context = new ScriptContextImpl(gameAPI, eventBus, messageBus, clientProvider);

            rpc.start();

            ClientImpl client = new ClientImpl(resolvedName, gameAPI, eventBus, pipe::isOpen, pump.region());
            clientProvider.putClient(resolvedName, client);

            ScriptRuntime runtime = new ScriptRuntime(context,
                    ConnectionContext::set, ConnectionContext::clear, eventBus::publish);
            runtime.setConnectionName(resolvedName);

            // Wire up ScriptManager so scripts can manage other scripts
            var scriptManager = new ScriptManagerImpl(runtime);
            context.setScriptManager(scriptManager);

            ReconnectController reconnect = new ReconnectController(rpc, pipe, resolvedName,
                    resolvedName, ReconnectPolicy.DEFAULT,
                    state -> { /* state is observable via Connection.currentReconnectState() */ },
                    eventBus::publish);
            reconnect.arm();

            Connection conn = new Connection(resolvedName, pipe, rpc, runtime);
            conn.setEventBus(eventBus);
            conn.setEventPump(pump);
            conn.setReconnectController(reconnect);
            connections.put(resolvedName, conn);
            activeConnectionName = resolvedName;
            if (onConnect != null) {
                try {
                    onConnect.accept(conn);
                } catch (RuntimeException e) {
                    log.warn("onConnect hook threw for '{}': {}", resolvedName, e.getMessage());
                }
            }
            out().println("Connected to pipe: " + pipe.getPipePath());
            if (connections.size() > 1) {
                out().println("Active connection set to '" + resolvedName + "'.");
            }
        } catch (Exception e) {
            out().println("Connection failed: " + e.getMessage());
        }
    }

    public void disconnect(String name, boolean force) {
        String target = name != null ? name : activeConnectionName;
        if (target == null || !connections.containsKey(target)) {
            out().println(target == null ? "No active connection." : "Connection not found: " + target);
            return;
        }
        Connection conn = connections.get(target);
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
        connections.remove(target);
        clientProvider.removeClient(target);
        conn.close();
        out().println("Disconnected from '" + target + "'.");

        if (target.equals(activeConnectionName)) {
            activeConnectionName = connections.isEmpty() ? null : connections.keySet().iterator().next();
            if (activeConnectionName != null) {
                out().println("Active connection switched to '" + activeConnectionName + "'.");
            }
        }
    }

    public void disconnect(String name) {
        disconnect(name, false);
    }

    public void disconnectAll(boolean force) {
        if (streamManager != null) {
            streamManager.stopAll(name -> connections.containsKey(name) ? connections.get(name) : null);
        }
        var iter = connections.entrySet().iterator();
        while (iter.hasNext()) {
            Connection conn = iter.next().getValue();
            if (conn.hasRunningScripts() && !force) {
                out().println("Skipping '" + conn.getName() + "' — has running scripts. Use --force to override.");
                continue;
            }
            conn.close();
            clientProvider.removeClient(conn.getName());
            out().println("Disconnected from '" + conn.getName() + "'.");
            iter.remove();
        }
        if (activeConnectionName != null && !connections.containsKey(activeConnectionName)) {
            activeConnectionName = connections.isEmpty() ? null : connections.keySet().iterator().next();
        }
    }

    public void disconnectAll() {
        disconnectAll(false);
    }

    public boolean setActive(String name) {
        if (!connections.containsKey(name)) return false;
        activeConnectionName = name;
        return true;
    }

    public List<BotScript> loadScripts() {
        return loadScriptReport().scripts();
    }

    /**
     * Loads scripts and returns the full {@link LoadReport} including per-JAR
     * failures. As a side effect, publishes a {@link ScriptLoadFailedEvent}
     * onto every active connection's event bus for each failure so the
     * notification overlay and Scripts panel can surface it.
     */
    public LoadReport loadScriptReport() {
        LoadReport report = SDNScriptLoader.loadLocalReport();
        this.lastLoadReport = report;
        for (ScriptLoadResult failure : report.failures()) {
            ScriptLoadFailedEvent event = new ScriptLoadFailedEvent(
                    failure.jar(), failure.error().orElse(new IllegalStateException("unknown")));
            broadcastEvent(event);
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
        Connection conn = connections.remove(connName);
        clientProvider.removeClient(connName);
        if (conn != null) {
            conn.close();
            out().println("Connection '" + connName + "' lost — removed.");
        }
        if (connName.equals(activeConnectionName)) {
            activeConnectionName = connections.isEmpty() ? null : connections.keySet().iterator().next();
            if (activeConnectionName != null) {
                out().println("Active connection switched to '" + activeConnectionName + "'.");
            }
        }
    }

    public boolean hasConnections() { return !connections.isEmpty(); }
    public boolean hasActiveConnection() { return activeConnectionName != null; }
    public String getActiveConnectionName() { return activeConnectionName; }
    public Connection getActiveConnection() { return activeConnectionName != null ? connections.get(activeConnectionName) : null; }
    public Collection<Connection> getConnections() { return connections.values(); }

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

    private static final Path GROUPS_FILE = Path.of(System.getProperty("user.home"), ".botwithus", "groups.json");

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
        if (!Files.exists(GROUPS_FILE)) {
            return;
        }
        try {
            String json = Files.readString(GROUPS_FILE);
            Gson gson = new Gson();
            Map<String, GroupData> data = gson.fromJson(json,
                    new TypeToken<LinkedHashMap<String, GroupData>>() {}.getType());
            if (data != null) {
                groups.clear();
                for (var entry : data.entrySet()) {
                    ConnectionGroup group = new ConnectionGroup(entry.getKey());
                    GroupData gd = entry.getValue();
                    if (gd.description != null) {
                        group.setDescription(gd.description);
                    }
                    if (gd.members != null) {
                        gd.members.forEach(group::add);
                    }
                    groups.put(entry.getKey(), group);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load groups", e);
        }
    }

    /** Persists current groups to ~/.botwithus/groups.json. */
    void saveGroups() {
        try {
            Files.createDirectories(GROUPS_FILE.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Map<String, GroupData> data = new LinkedHashMap<>();
            for (var entry : groups.entrySet()) {
                ConnectionGroup g = entry.getValue();
                data.put(entry.getKey(), new GroupData(g.getDescription(), new ArrayList<>(g.getConnectionNames())));
            }
            Files.writeString(GROUPS_FILE, gson.toJson(data));
        } catch (Exception e) {
            log.error("Failed to save groups", e);
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

    public Map<String, ConnectionGroup> getGroups() {
        return Collections.unmodifiableMap(groups);
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
        Path scriptsDir = Path.of("scripts");
        if (!Files.isDirectory(scriptsDir)) {
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

    private Path resolveBwuDll() {
        return BwuClient.resolve(getClass());
    }
}
