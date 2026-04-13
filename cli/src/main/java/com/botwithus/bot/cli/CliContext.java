package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.blueprint.BlueprintGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.blueprint.execution.BlueprintBotScript;
import com.botwithus.bot.core.blueprint.serialization.BlueprintSerializer;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.EventDispatcher;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRuntime;

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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private com.botwithus.bot.cli.watch.ScriptWatcher scriptWatcher;
    private ScriptProfileStore profileStore;
    private AutoStartManager autoStartManager;
    private ClientManager clientManager;
    private ManagementScriptRuntime managementRuntime;

    public CliContext(LogBuffer logBuffer, LogCapture logCapture) {
        this.logBuffer = logBuffer;
        this.logCapture = logCapture;
        this.clientManager = new ClientManager(this);
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
        if (managementRuntime != null) return;
        var messageBus = new MessageBusImpl();
        var sharedState = new SharedStateImpl();

        GameLauncher launcher = null;
        if (existingClient != null) {
            launcher = new BwuGameLauncher(existingClient);
            log.info("Game launcher reusing existing BwuClient instance");
        } else {
            // Optionally load the native game launcher (bwu.dll)
            // 1) Check filesystem (covers jlink/prod where DLL sits next to the executable)
            // 2) Fall back to extracting from bundled resources (covers Gradle dev runs)
            Path dllPath = resolveBwuDll();
            if (dllPath != null) {
                var bwuClient = BwuClient.load(dllPath);
                if (bwuClient.isPresent()) {
                    launcher = new BwuGameLauncher(bwuClient.get());
                    log.info("Game launcher (bwu.dll) loaded from {}", dllPath);
                } else {
                    log.debug("Game launcher (bwu.dll) found but failed to load from {}", dllPath);
                }
            } else {
                log.debug("Game launcher (bwu.dll) not available — management scripts will not have launcher access");
            }
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
        if (managementRuntime == null) initManagementRuntime();
        return ManagementScriptLoader.loadScripts();
    }

    public void connect(String pipeName) {
        String connName = pipeName != null ? pipeName : "BotWithUs";
        if (connections.containsKey(connName)) {
            out().println("Already connected to '" + connName + "'. Use 'use " + connName + "' to switch.");
            return;
        }
        try {
            PipeClient pipe = pipeName != null ? new PipeClient(pipeName) : new PipeClient();
            RpcClient rpc = new RpcClient(pipe);
            rpc.setConnectionName(connName);
            EventBusImpl eventBus = new EventBusImpl();
            MessageBusImpl messageBus = new MessageBusImpl();
            GameAPIImpl gameAPI = new GameAPIImpl(rpc);
            ClientImpl client = new ClientImpl(connName, gameAPI, eventBus, pipe::isOpen);
            clientProvider.putClient(connName, client);
            ScriptContextImpl context = new ScriptContextImpl(gameAPI, eventBus, messageBus, clientProvider);

            var dispatcher = new EventDispatcher(eventBus);
            dispatcher.bindAutoSubscription(gameAPI);
            rpc.setEventHandler(dispatcher::dispatch);
            rpc.start();

            ScriptRuntime runtime = new ScriptRuntime(context);
            runtime.setConnectionName(connName);

            // Wire up ScriptManager so scripts can manage other scripts
            var scriptManager = new ScriptManagerImpl(runtime);
            context.setScriptManager(scriptManager);

            Connection conn = new Connection(connName, pipe, rpc, runtime);
            conn.setEventBus(eventBus);
            connections.put(connName, conn);
            activeConnectionName = connName;
            out().println("Connected to pipe: " + pipe.getPipePath());
            if (connections.size() > 1) {
                out().println("Active connection set to '" + connName + "'.");
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
        return SDNScriptLoader.loadScripts();
    }

    /**
     * Scans the {@code scripts/blueprints/} directory for {@code *.blueprint.json} files,
     * deserializes each into a {@link BlueprintGraph}, and wraps them as {@link BlueprintBotScript} instances.
     */
    public List<BotScript> loadBlueprints() {
        Path dir = Path.of("scripts", "blueprints");
        if (!Files.isDirectory(dir)) return List.of();
        try {
            List<BlueprintGraph> graphs = BlueprintSerializer.loadAllFromDirectory(dir);
            List<BotScript> scripts = new ArrayList<>();
            for (BlueprintGraph graph : graphs) {
                scripts.add(new BlueprintBotScript(graph));
            }
            return scripts;
        } catch (Exception e) {
            log.error("Failed to load blueprints", e);
            return List.of();
        }
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

    public void setConfigPanelOpener(Consumer<ScriptRunner> opener) { this.configPanelOpener = opener; }
    public void openConfigPanel(ScriptRunner runner) {
        if (configPanelOpener != null) configPanelOpener.accept(runner);
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
        if (!Files.exists(GROUPS_FILE)) return;
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
                    if (gd.description != null) group.setDescription(gd.description);
                    if (gd.members != null) gd.members.forEach(group::add);
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
        if (removed) saveGroups();
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
        if (group == null) return List.of();
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
        if (scriptWatcher != null && scriptWatcher.isRunning()) return;
        java.nio.file.Path scriptsDir = java.nio.file.Path.of("scripts");
        if (!java.nio.file.Files.isDirectory(scriptsDir)) return;
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

    /**
     * Resolves bwu.dll: checks the filesystem first (for jlink/prod), then
     * extracts from bundled resources to a temp file (for Gradle dev runs).
     *
     * @return path to the DLL, or {@code null} if unavailable
     */
    private Path resolveBwuDll() {
        // Filesystem — DLL next to the executable or in the working directory
        Path fsPath = Path.of("bwu.dll");
        if (Files.isRegularFile(fsPath)) {
            return fsPath;
        }

        // Bundled resource — extract to temp
        try (InputStream in = getClass().getResourceAsStream("/native/bwu.dll")) {
            if (in == null) return null;
            Path tmp = Files.createTempFile("bwu", ".dll");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Extracted bwu.dll from resources to {}", tmp);
            return tmp;
        } catch (IOException e) {
            log.warn("Failed to extract bwu.dll from resources: {}", e.getMessage());
            return null;
        }
    }
}
