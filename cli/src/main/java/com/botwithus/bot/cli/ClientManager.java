package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages clients (connections) and groups, providing cross-client script
 * lifecycle operations.
 *
 * <p>Groups can have descriptions to categorise their purpose
 * (e.g. "Skillers", "Combat"). Scripts can be started/stopped on individual
 * clients, across a group, or across all connected clients at once.
 */
public class ClientManager {

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

    /** Returns the names of all connected clients. */
    public List<String> getClientNames() {
        return ctx.getConnections().stream()
                .map(Connection::getName)
                .toList();
    }

    /** Returns {@code true} if the named client is connected and alive. */
    public boolean isClientAlive(String name) {
        Connection conn = getClient(name);
        return conn != null && conn.isAlive();
    }

    // ── Group management ────────────────────────────────────────────────────

    /** Creates a new group with a name and description. */
    public ConnectionGroup createGroup(String name, String description) {
        ConnectionGroup group = ctx.getGroup(name);
        if (group != null) {
            return group;
        }
        group = new ConnectionGroup(name, description);
        ctx.getGroups(); // trigger existence
        // Use CliContext's createGroup then set description
        ctx.createGroup(name);
        ConnectionGroup created = ctx.getGroup(name);
        if (created != null && description != null) {
            created.setDescription(description);
        }
        return created;
    }

    /** Creates a group with no description. */
    public ConnectionGroup createGroup(String name) {
        return createGroup(name, null);
    }

    /** Deletes a group by name. Returns {@code true} if it existed. */
    public boolean deleteGroup(String name) {
        return ctx.deleteGroup(name);
    }

    /** Returns the group with the given name, or {@code null}. */
    public ConnectionGroup getGroup(String name) {
        return ctx.getGroup(name);
    }

    /** Returns all groups. */
    public Map<String, ConnectionGroup> getGroups() {
        return ctx.getGroups();
    }

    /** Adds a client to a group. Returns {@code false} if group or client not found. */
    public boolean addToGroup(String groupName, String clientName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return false;
        group.add(clientName);
        return true;
    }

    /** Removes a client from a group. Returns {@code false} if group not found. */
    public boolean removeFromGroup(String groupName, String clientName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return false;
        group.remove(clientName);
        return true;
    }

    /** Returns the active (alive) connections in a group. */
    public List<Connection> getGroupClients(String groupName) {
        return ctx.getGroupConnections(groupName);
    }

    // ── Cross-client script operations ──────────────────────────────────────

    /**
     * Starts a script by name on a specific client.
     *
     * @return result describing success or failure
     */
    public ScriptOpResult startScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return ScriptOpResult.clientNotFound(clientName);
        if (!conn.isAlive()) return ScriptOpResult.clientDisconnected(clientName);

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) return ScriptOpResult.scriptNotFound(clientName, scriptName);
        if (runner.isRunning()) return ScriptOpResult.alreadyRunning(clientName, scriptName);

        runner.start();
        return ScriptOpResult.ok(clientName, scriptName, "started");
    }

    /**
     * Stops a script by name on a specific client.
     */
    public ScriptOpResult stopScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return ScriptOpResult.clientNotFound(clientName);
        if (!conn.isAlive()) return ScriptOpResult.clientDisconnected(clientName);

        if (conn.getRuntime().stopScript(scriptName)) {
            return ScriptOpResult.ok(clientName, scriptName, "stopped");
        }
        return ScriptOpResult.scriptNotFound(clientName, scriptName);
    }

    /**
     * Restarts a script on a specific client.
     */
    public ScriptOpResult restartScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) return ScriptOpResult.clientNotFound(clientName);
        if (!conn.isAlive()) return ScriptOpResult.clientDisconnected(clientName);

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) return ScriptOpResult.scriptNotFound(clientName, scriptName);

        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(2000);
        }
        runner.start();
        return ScriptOpResult.ok(clientName, scriptName, "restarted");
    }

    /**
     * Starts a script on all clients in a group.
     *
     * @return list of results, one per client in the group
     */
    public List<ScriptOpResult> startScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "start");
    }

    /**
     * Stops a script on all clients in a group.
     */
    public List<ScriptOpResult> stopScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "stop");
    }

    /**
     * Restarts a script on all clients in a group.
     */
    public List<ScriptOpResult> restartScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "restart");
    }

    /**
     * Starts a script on every connected client.
     */
    public List<ScriptOpResult> startScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "start");
    }

    /**
     * Stops a script on every connected client.
     */
    public List<ScriptOpResult> stopScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "stop");
    }

    /**
     * Restarts a script on every connected client.
     */
    public List<ScriptOpResult> restartScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "restart");
    }

    /**
     * Stops all scripts on all clients in a group.
     */
    public List<ScriptOpResult> stopAllScriptsOnGroup(String groupName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return List.of(ScriptOpResult.groupNotFound(groupName));

        List<ScriptOpResult> results = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            conn.getRuntime().stopAll();
            results.add(ScriptOpResult.ok(conn.getName(), "*", "stopped all"));
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    /**
     * Stops all scripts on every connected client.
     */
    public void stopAllScriptsOnAll() {
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                conn.getRuntime().stopAll();
            }
        }
    }

    // ── Cross-client status ─────────────────────────────────────────────────

    /**
     * Returns a snapshot of all scripts across all clients.
     */
    public List<ClientScriptStatus> getStatusAll() {
        List<ClientScriptStatus> result = new ArrayList<>();
        for (Connection conn : getClients()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                result.add(toStatus(conn, runner));
            }
        }
        return result;
    }

    /**
     * Returns a snapshot of scripts for clients in a group.
     */
    public List<ClientScriptStatus> getStatusForGroup(String groupName) {
        List<ClientScriptStatus> result = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                result.add(toStatus(conn, runner));
            }
        }
        return result;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private List<ScriptOpResult> executeOnGroup(String groupName, String scriptName, String action) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) return List.of(ScriptOpResult.groupNotFound(groupName));

        List<Connection> clients = getGroupClients(groupName);
        if (clients.isEmpty()) {
            List<ScriptOpResult> results = new ArrayList<>();
            results.add(new ScriptOpResult(false, groupName, scriptName, "no active clients in group"));
            return results;
        }

        List<ScriptOpResult> results = new ArrayList<>();
        for (Connection conn : clients) {
            results.add(executeAction(conn.getName(), scriptName, action));
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    private List<ScriptOpResult> executeOnAll(String scriptName, String action) {
        List<ScriptOpResult> results = new ArrayList<>();
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                results.add(executeAction(conn.getName(), scriptName, action));
            }
        }
        return results;
    }

    private ScriptOpResult executeAction(String clientName, String scriptName, String action) {
        return switch (action) {
            case "start" -> startScript(clientName, scriptName);
            case "stop" -> stopScript(clientName, scriptName);
            case "restart" -> restartScript(clientName, scriptName);
            default -> new ScriptOpResult(false, clientName, scriptName, "unknown action: " + action);
        };
    }

    private void addDisconnectedWarnings(ConnectionGroup group, List<ScriptOpResult> results) {
        List<Connection> activeClients = getGroupClients(group.getName());
        for (String memberName : group.getConnectionNames()) {
            if (activeClients.stream().noneMatch(c -> c.getName().equals(memberName))) {
                results.add(ScriptOpResult.clientDisconnected(memberName));
            }
        }
    }

    private ClientScriptStatus toStatus(Connection conn, ScriptRunner runner) {
        ScriptManifest m = runner.getManifest();
        return new ClientScriptStatus(
                conn.getName(),
                runner.getScriptName(),
                m != null ? m.version() : "?",
                runner.isRunning(),
                conn.isAlive()
        );
    }

    // ── Result types ────────────────────────────────────────────────────────

    /**
     * Result of a script operation on a specific client.
     */
    public record ScriptOpResult(boolean success, String clientName, String scriptName, String message) {

        static ScriptOpResult ok(String client, String script, String action) {
            return new ScriptOpResult(true, client, script, action);
        }

        static ScriptOpResult clientNotFound(String client) {
            return new ScriptOpResult(false, client, null, "client not found");
        }

        static ScriptOpResult clientDisconnected(String client) {
            return new ScriptOpResult(false, client, null, "client disconnected");
        }

        static ScriptOpResult scriptNotFound(String client, String script) {
            return new ScriptOpResult(false, client, script, "script not found");
        }

        static ScriptOpResult alreadyRunning(String client, String script) {
            return new ScriptOpResult(false, client, script, "already running");
        }

        static ScriptOpResult groupNotFound(String group) {
            return new ScriptOpResult(false, group, null, "group not found");
        }
    }

    /**
     * Snapshot of a script's state on a specific client.
     */
    public record ClientScriptStatus(
            String clientName,
            String scriptName,
            String version,
            boolean running,
            boolean clientAlive
    ) {}
}
