package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

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

    /**
     * Default timeout (ms) given to a script's stop hook before it is
     * restarted. Shared across CLI commands and GUI panels that implement
     * a "restart" affordance so the user-visible wait stays consistent.
     */
    public static final int RESTART_STOP_TIMEOUT_MS = 2000;

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
        if (ctx.getGroup(name) != null) {
            return false;
        }
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
        if (existing != null) {
            return existing;
        }
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
        if (ctx.getGroup(groupName) == null) {
            return false;
        }
        ctx.addToGroup(groupName, clientName);
        return true;
    }

    @Override
    public boolean removeFromGroup(String groupName, String clientName) {
        if (ctx.getGroup(groupName) == null) {
            return false;
        }
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
        if (conn == null) {
            return new OpResult(false, clientName, scriptName, "client not found");
        }
        if (!conn.isAlive()) {
            return new OpResult(false, clientName, scriptName, "client disconnected");
        }

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) {
            return new OpResult(false, clientName, scriptName, "script not found");
        }
        if (runner.isRunning()) {
            return new OpResult(false, clientName, scriptName, "already running");
        }

        runner.start();
        return new OpResult(true, clientName, scriptName, "started");
    }

    @Override
    public OpResult stopScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) {
            return new OpResult(false, clientName, scriptName, "client not found");
        }
        if (!conn.isAlive()) {
            return new OpResult(false, clientName, scriptName, "client disconnected");
        }

        if (conn.getRuntime().stopScript(scriptName)) {
            return new OpResult(true, clientName, scriptName, "stopped");
        }
        return new OpResult(false, clientName, scriptName, "script not found");
    }

    @Override
    public OpResult restartScript(String clientName, String scriptName) {
        Connection conn = getClient(clientName);
        if (conn == null) {
            return new OpResult(false, clientName, scriptName, "client not found");
        }
        if (!conn.isAlive()) {
            return new OpResult(false, clientName, scriptName, "client disconnected");
        }

        ScriptRunner runner = conn.getRuntime().findRunner(scriptName);
        if (runner == null) {
            return new OpResult(false, clientName, scriptName, "script not found");
        }

        if (runner.isRunning()) {
            runner.stop();
            runner.awaitStop(RESTART_STOP_TIMEOUT_MS);
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
        if (group == null) {
            return List.of(new OpResult(false, groupName, null, "group not found"));
        }

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

    // ── Single-client schedule operations ───────────────────────────────────

    @Override
    public ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay) {
        return withValidatedClient(clientName, scriptName, conn -> {
            String id = conn.getScheduler().runAfter(scriptName, delay);
            return new ScheduleOpResult(true, clientName, scriptName, id, "scheduled");
        });
    }

    @Override
    public ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay, Map<String, Object> config) {
        return withValidatedClient(clientName, scriptName, conn -> {
            String id = conn.getScheduler().runAfter(scriptName, delay, config);
            return new ScheduleOpResult(true, clientName, scriptName, id, "scheduled");
        });
    }

    @Override
    public ScheduleOpResult scheduleScriptAt(String clientName, String scriptName, Instant at) {
        return withValidatedClient(clientName, scriptName, conn -> {
            String id = conn.getScheduler().runAt(scriptName, at);
            return new ScheduleOpResult(true, clientName, scriptName, id, "scheduled");
        });
    }

    @Override
    public ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval) {
        return withValidatedClient(clientName, scriptName, conn -> {
            String id = conn.getScheduler().runEvery(scriptName, interval);
            return new ScheduleOpResult(true, clientName, scriptName, id, "scheduled");
        });
    }

    @Override
    public ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval, Duration maxDuration) {
        return withValidatedClient(clientName, scriptName, conn -> {
            String id = conn.getScheduler().runEvery(scriptName, interval, maxDuration);
            return new ScheduleOpResult(true, clientName, scriptName, id, "scheduled");
        });
    }

    // ── Group schedule operations ───────────────────────────────────────────

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay) {
        return scheduleOnGroup(groupName, scriptName, (c, s) -> scheduleScript(c, s, delay));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay, Map<String, Object> config) {
        return scheduleOnGroup(groupName, scriptName, (c, s) -> scheduleScript(c, s, delay, config));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupAt(String groupName, String scriptName, Instant at) {
        return scheduleOnGroup(groupName, scriptName, (c, s) -> scheduleScriptAt(c, s, at));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName, Duration interval) {
        return scheduleOnGroup(groupName, scriptName, (c, s) -> scheduleScriptEvery(c, s, interval));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName, Duration interval, Duration maxDuration) {
        return scheduleOnGroup(groupName, scriptName, (c, s) -> scheduleScriptEvery(c, s, interval, maxDuration));
    }

    // ── All-client schedule operations ──────────────────────────────────────

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay) {
        return scheduleOnAll(scriptName, (c, s) -> scheduleScript(c, s, delay));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay, Map<String, Object> config) {
        return scheduleOnAll(scriptName, (c, s) -> scheduleScript(c, s, delay, config));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllAt(String scriptName, Instant at) {
        return scheduleOnAll(scriptName, (c, s) -> scheduleScriptAt(c, s, at));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval) {
        return scheduleOnAll(scriptName, (c, s) -> scheduleScriptEvery(c, s, interval));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval, Duration maxDuration) {
        return scheduleOnAll(scriptName, (c, s) -> scheduleScriptEvery(c, s, interval, maxDuration));
    }

    // ── Schedule cancellation ───────────────────────────────────────────────

    @Override
    public boolean cancelSchedule(String clientName, String scheduleId) {
        Connection conn = getClient(clientName);
        if (conn == null || !conn.isAlive()) {
            return false;
        }
        return conn.getScheduler().cancel(scheduleId);
    }

    @Override
    public List<ScheduleOpResult> cancelAllSchedulesOnGroup(String groupName) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) {
            return List.of(new ScheduleOpResult(false, groupName, null, null, "group not found"));
        }
        List<ScheduleOpResult> results = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            for (ScriptScheduler.ScheduledEntry entry : conn.getScheduler().listScheduled()) {
                boolean ok = conn.getScheduler().cancel(entry.id());
                results.add(new ScheduleOpResult(ok, conn.getName(), entry.scriptName(), entry.id(),
                        ok ? "cancelled" : "cancel failed"));
            }
        }
        addScheduleDisconnectedWarnings(group, results);
        return results;
    }

    @Override
    public void cancelAllSchedules() {
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                conn.getScheduler().cancelAll();
            }
        }
    }

    // ── Schedule queries ────────────────────────────────────────────────────

    @Override
    public List<ScheduledScriptEntry> listScheduled() {
        List<ScheduledScriptEntry> result = new ArrayList<>();
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                collectSchedules(conn, result);
            }
        }
        return result;
    }

    @Override
    public List<ScheduledScriptEntry> listScheduledForClient(String clientName) {
        Connection conn = getClient(clientName);
        if (conn == null || !conn.isAlive()) {
            return List.of();
        }
        List<ScheduledScriptEntry> result = new ArrayList<>();
        collectSchedules(conn, result);
        return result;
    }

    @Override
    public List<ScheduledScriptEntry> listScheduledForGroup(String groupName) {
        List<ScheduledScriptEntry> result = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            collectSchedules(conn, result);
        }
        return result;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private List<OpResult> executeOnGroup(String groupName, String scriptName, String action) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) {
            return List.of(new OpResult(false, groupName, scriptName, "group not found"));
        }

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

    private ScheduleOpResult withValidatedClient(String clientName, String scriptName,
                                                 Function<Connection, ScheduleOpResult> op) {
        Connection conn = getClient(clientName);
        if (conn == null) {
            return new ScheduleOpResult(false, clientName, scriptName, null, "client not found");
        }
        if (!conn.isAlive()) {
            return new ScheduleOpResult(false, clientName, scriptName, null, "client disconnected");
        }
        if (conn.getRuntime().findRunner(scriptName) == null) {
            return new ScheduleOpResult(false, clientName, scriptName, null, "script not found");
        }
        return op.apply(conn);
    }

    private List<ScheduleOpResult> scheduleOnGroup(String groupName, String scriptName,
                                                   BiFunction<String, String, ScheduleOpResult> perClient) {
        ConnectionGroup group = ctx.getGroup(groupName);
        if (group == null) {
            return List.of(new ScheduleOpResult(false, groupName, scriptName, null, "group not found"));
        }
        List<Connection> clients = getGroupClients(groupName);
        if (clients.isEmpty()) {
            return List.of(new ScheduleOpResult(false, groupName, scriptName, null, "no active clients in group"));
        }
        List<ScheduleOpResult> results = new ArrayList<>();
        for (Connection conn : clients) {
            results.add(perClient.apply(conn.getName(), scriptName));
        }
        addScheduleDisconnectedWarnings(group, results);
        return results;
    }

    private List<ScheduleOpResult> scheduleOnAll(String scriptName,
                                                 BiFunction<String, String, ScheduleOpResult> perClient) {
        List<ScheduleOpResult> results = new ArrayList<>();
        for (Connection conn : getClients()) {
            if (conn.isAlive()) {
                results.add(perClient.apply(conn.getName(), scriptName));
            }
        }
        return results;
    }

    private void addScheduleDisconnectedWarnings(ConnectionGroup group, List<ScheduleOpResult> results) {
        List<Connection> activeClients = getGroupClients(group.getName());
        for (String memberName : group.getConnectionNames()) {
            if (activeClients.stream().noneMatch(c -> c.getName().equals(memberName))) {
                results.add(new ScheduleOpResult(false, memberName, null, null, "client disconnected"));
            }
        }
    }

    private void collectSchedules(Connection conn, List<ScheduledScriptEntry> out) {
        for (ScriptScheduler.ScheduledEntry entry : conn.getScheduler().listScheduled()) {
            out.add(new ScheduledScriptEntry(
                    conn.getName(),
                    entry.id(),
                    entry.scriptName(),
                    entry.nextRun(),
                    entry.interval(),
                    entry.maxDuration()));
        }
    }
}
