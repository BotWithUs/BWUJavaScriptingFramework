package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CliContext {

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

    public CliContext(LogBuffer logBuffer, LogCapture logCapture) {
        this.logBuffer = logBuffer;
        this.logCapture = logCapture;
    }

    public void setStreamManager(StreamManager sm) { this.streamManager = sm; }
    public StreamManager getStreamManager() { return streamManager; }

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

            var dispatcher = new com.botwithus.bot.core.impl.EventDispatcher(eventBus);
            dispatcher.bindAutoSubscription(gameAPI);
            rpc.setEventHandler(dispatcher::dispatch);
            rpc.start();

            ScriptRuntime runtime = new ScriptRuntime(context);
            runtime.setConnectionName(connName);
            Connection conn = new Connection(connName, pipe, rpc, runtime);
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
        return ScriptLoader.loadScripts();
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

    // --- Connection Group management ---

    public void createGroup(String name) {
        groups.put(name, new ConnectionGroup(name));
    }

    public boolean deleteGroup(String name) {
        return groups.remove(name) != null;
    }

    public ConnectionGroup getGroup(String name) {
        return groups.get(name);
    }

    public Map<String, ConnectionGroup> getGroups() {
        return Collections.unmodifiableMap(groups);
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
}
