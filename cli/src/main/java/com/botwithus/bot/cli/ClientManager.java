package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages clients (connections) and groups, providing cross-client script
 * lifecycle operations. Implements {@link ClientOrchestrator} so that
 * management scripts can use it via the API interface.
 *
 * <p>Groups can have descriptions to categorise their purpose
 * (e.g. "Skillers", "Combat"). Scripts can be started/stopped on individual
 * clients, across a group, or across all connected clients at once.
 */
public class ClientManager implements ClientOrchestrator {

    private final CliContext ctx;

    public ClientManager(CliContext ctx) {
        this.ctx = ctx;
    }

    // ── Client queries ──────────────────────────────────────────────────────

    /** Returns all currently connected clients. */
    public Collection<Connection> getClients() {
        return ctx.getConnections();
    }

    /** Returns a connected client by name, or {@code null} if not found/disconnected. */
    public Connection getClient(String name) {
        for (Connection conn : ctx.getConnections()) {
            if (conn.getName().equals(name)) {
                return conn;
            }
        }
        return null;
    }

    @Override
    public List<String> getClientNames() {
        return ctx.getConnections().stream()
                .map(Connection::getName)
                .toList();
    }

    @Override
    public boolean isClientAlive(String name) {
        Connection conn = getClient(name);
        return conn != null && conn.isAlive();
    }

    // ── Group management ────────────────────────────────────────────────────

    @Override
    public boolean createGroup(String name, String description) {
        if (ctx.getGroup(name) != null) return false;
        ctx.createGroup(name);
        ConnectionGroup created = ctx.getGroup(name);
        if (created != null && description != null) {
            created.setDescription(description);
        }
        return true;
    }

    /** Creates a new group, returning the group object. */
    public ConnectionGroup createGroupAndGet(String name, String description) {
        ConnectionGroup existing = ctx.getGroup(name);
        if (existing != null) return existing;
        ctx.createGroup(name);
        ConnectionGroup created = ctx.getGroup(name);
        if (created != null && description != null) {
            created.setDescription(description);
        }
        return created;
    }

    @Override
    public boolean deleteGroup(String name) {
        return ctx.deleteGroup(name);
    }

    /** Returns the group with the given name, or {@code null}. */
    public ConnectionGroup getGroup(String name) {
        return ctx.getGroup(name);
    }

    /** Returns all groups as a map. */
    public Map<String, ConnectionGroup> getGroups() {
        return ctx.getGroups();
    }

    @Override
    public Set<String> getGroupNames() {
        return ctx.getGroups().keySet();
    }

    @Override
    public String getGroupDescription(String groupName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        return group != null ? group.getDescription() : null;
    }

    @Override
    public void setGroupDescription(String groupName, String description) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group != null) {
            group.setDescription(description);
            ctx.saveGroups();
        }
    }

    @Override
    public Set<String> getGroupMembers(String groupName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        return group != null ? group.getConnectionNames() : Set.of();
    }

    @Override
    public boolean addToGroup(String groupName, String clientName) {
        if (ctx.getGroup(groupName) == null) return false;
        ctx.addToGroup(groupName, clientName);
        return true;
    }

    @Override
    public boolean removeFromGroup(String groupName, String clientName) {
        if (ctx.getGroup(groupName) == null) return false;
        ctx.removeFromGroup(groupName, clientName);
        return true;
    }

    /** Returns the active (alive) connections in a group. */
    public List<Connection> getGroupClients(String groupName) {
        return ctx.getGroupConnections(groupName);
    }

    // ── Single-client script operations ─────────────────────────────────────

    @Override
    public OpResult startScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return new OpResult(false, clientName, scriptName, "client not found");
        if (!conn.isAlive()) return new OpResult(false, clientName, scriptName, "client disconnected");

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) return new OpResult(false, clientName, scriptName, "script not found");
        if (runner.isRunning()) return new OpResult(false, clientName, scriptName, "already running");

        runner.start();
        return new OpResult(true, clientName, scriptName, "started");
    }

    @Override
    public OpResult stopScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return new OpResult(false, clientName, scriptName, "client not found");
        if (!conn.isAlive()) return new OpResult(false, clientName, scriptName, "client disconnected");

        if (conn.getRuntime().stopScript(scriptName)) {
            return new OpResult(true, clientName, scriptName, "stopped");
        }
        return new OpResult(false, clientName, scriptName, "script not found");
    }

    @Override
    public OpResult restartScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return new OpResult(false, clientName, scriptName, "client not found");
        if (!conn.isAlive()) return new OpResult(false, clientName, scriptName, "client disconnected");

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) return new OpResult(false, clientName, scriptName, "script not found");

        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        return new OpResult(true, clientName, scriptName, "restarted");
    }

    // ── Group script operations ─────────────────────────────────────────────

    @Override
    public List<OpResult> startScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "start");
    }

    @Override
    public List<OpResult> stopScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "stop");
    }

    @Override
    public List<OpResult> restartScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "restart");
    }

    @Override
    public List<OpResult> stopAllScriptsOnGroup(String groupName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return List.of(new OpResult(false, groupName, null, "group not found"));

        List<OpResult> results = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            conn.getRuntime().stopAll();
            results.add(new OpResult(true, conn.getName(), "*", "stopped all"));
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    // ── All-client script operations ────────────────────────────────────────

    @Override
    public List<OpResult> startScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "start");
    }

    @Override
    public List<OpResult> stopScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "stop");
    }

    @Override
    public List<OpResult> restartScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "restart");
    }

    @Override
    public void stopAllScriptsOnAll() {
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                conn.getRuntime().stopAll();
            }
        }
    }

    // ── Status ──────────────────────────────────────────────────────────────

    @Override
    public List<ScriptStatusEntry> getStatusAll() {
        List<ScriptStatusEntry> result = new ArrayList<>();
        for (Connection conn : getClients()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                result.add(toStatusEntry(conn, runner));
            }
        }
        return result;
    }

    @Override
    public List<ScriptStatusEntry> getStatusForGroup(String groupName) {
        List<ScriptStatusEntry> result = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                result.add(toStatusEntry(conn, runner));
            }
        }
        return result;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private List<OpResult> executeOnGroup(String groupName, String scriptName, String action) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return List.of(new OpResult(false, groupName, scriptName, "group not found"));

        List<Connection> clients = getGroupClients(groupName);
        if (clients.isEmpty()) {
            return List.of(new OpResult(false, groupName, scriptName, "no active clients in group"));
        }

        List<OpResult> results = new ArrayList<>();
        for (Connection conn : clients) {
            results.add(executeAction(conn.getName(), scriptName, action));
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    private List<OpResult> executeOnAll(String scriptName, String action) {
        List<OpResult> results = new ArrayList<>();
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                results.add(executeAction(conn.getName(), scriptName, action));
            }
        }
        return results;
    }

    private OpResult executeAction(String clientName, String scriptName, String action) {
        return switch (action) {
            case "start" -> startScript(clientName, scriptName);
            case "stop" -> stopScript(clientName, scriptName);
            case "restart" -> restartScript(clientName, scriptName);
            default -> new OpResult(false, clientName, scriptName, "unknown action: " + action);
        };
    }

    private void addDisconnectedWarnings(ConnectionGroup group, List<OpResult> results) {
        List<Connection> activeClients = getGroupClients(group.getName());
        for (String memberName : group.getConnectionNames()) {
            if (activeClients.stream().noneMatch(c -> c.getName().equals(memberName))) {
                results.add(new OpResult(false, memberName, null, "client disconnected"));
            }
        }
    }

    private ScriptStatusEntry toStatusEntry(Connection conn, ScriptRunner runner) {
        ScriptManifest m = runner.getManifest();
        return new ScriptStatusEntry(
                conn.getName(),
                runner.getScriptName(),
                m != null ? m.version() : "?",
                runner.isRunning(),
                conn.isAlive()
        );
    }
}
