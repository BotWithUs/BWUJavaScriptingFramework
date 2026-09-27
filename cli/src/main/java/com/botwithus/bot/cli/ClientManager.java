package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ScriptScheduler;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.groups.StartWhenBackQueue;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** The result message for a group member that is not connected. */
    public static final String CLIENT_DISCONNECTED = "client disconnected";
    /** The result message for a group member carried over by pipe whose account is not known. */
    public static final String UNKNOWN_CLIENT = "unknown client";
    /** The result message for a stop that cancelled a queued start on a client that is not connected. */
    public static final String QUEUED_START_CANCELLED = "queued start cancelled";

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
    //
    // The orchestrator API names groups; the store keys them by id, so these
    // resolve the name first. A client is named by its connection's pipe, as
    // everywhere in this API, or, for one that is not connected, by its account
    // UUID. Members are stored by account.

    @Override
    public boolean createGroup(String name, String description) {
        return name != null && groups().create(name, Optional.ofNullable(description)).isPresent();
    }

    /** Creates a group, or returns the one that already has {@code name}; empty if {@code name} is blank. */
    public Optional<ClientGroup> createGroupAndGet(String name, String description) {
        Optional<ClientGroup> existing = groups().byName(name);
        return existing.isPresent() ? existing : groups().create(name, Optional.ofNullable(description));
    }

    @Override
    public boolean deleteGroup(String name) {
        return groups().byName(name).map(group -> groups().delete(group.id())).orElse(false);
    }

    /** The group called {@code name}. */
    public Optional<ClientGroup> getGroup(String name) {
        return groups().byName(name);
    }

    /** Every group, in the order they were created. */
    public List<ClientGroup> getGroups() {
        return groups().all();
    }

    @Override
    public Set<String> getGroupNames() {
        Set<String> names = new LinkedHashSet<>();
        groups().all().forEach(group -> names.add(group.name()));
        return names;
    }

    @Override
    public String getGroupDescription(String groupName) {
        return groups().byName(groupName).flatMap(ClientGroup::description).orElse(null);
    }

    @Override
    public void setGroupDescription(String groupName, String description) {
        groups().byName(groupName).ifPresent(group ->
                groups().setDescription(group.id(), Optional.ofNullable(description)));
    }

    /**
     * The pipe names of the group's members that are connected now, which are
     * the names the rest of this API takes. A member that is not connected has
     * no pipe, so it is not included.
     */
    @Override
    public Set<String> getGroupMembers(String groupName) {
        Set<String> names = new LinkedHashSet<>();
        getGroupClients(groupName).forEach(conn -> names.add(conn.getName()));
        return names;
    }

    /** @return {@code true} if the client is a member afterwards; see {@link #addMember} for why not */
    @Override
    public boolean addToGroup(String groupName, String clientName) {
        return switch (addMember(groupName, clientName)) {
            case MemberChange.Added _, MemberChange.AlreadyMember _ -> true;
            case MemberChange.Refused _, MemberChange.NoSuchGroup _ -> false;
        };
    }

    /**
     * Adds a client to the group called {@code groupName}.
     *
     * @param client the pipe of a connected client, or the account UUID of one
     *               that is not connected
     * @return what happened; a {@link MemberChange.Refused} says why, in words
     *         for the user
     */
    public MemberChange addMember(String groupName, String client) {
        Optional<ClientGroup> group = groups().byName(groupName);
        if (group.isEmpty()) {
            return new MemberChange.NoSuchGroup();
        }
        Optional<ClientKey> key = keyOfClient(client);
        if (key.isEmpty()) {
            return new MemberChange.Refused("No client called '" + client
                    + "' is connected, and it is not an account UUID.");
        }
        return groups().addMember(group.get().id(), key.get());
    }

    /**
     * Removes a client from the group called {@code groupName}.
     *
     * @param clientName the pipe of a connected client, the account UUID of any
     *                   member, or the pipe of an unresolved member
     * @return {@code true} if it was a member
     */
    @Override
    public boolean removeFromGroup(String groupName, String clientName) {
        Optional<ClientGroup> group = groups().byName(groupName);
        if (group.isEmpty()) {
            return false;
        }
        GroupId id = group.get().id();
        Optional<String> account = keyOfClient(clientName).flatMap(ClientKey::accountUuid);
        boolean removed = account.isPresent() && groups().removeMember(id, account.get());
        return removed || groups().removeUnresolved(id, clientName);
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
            return new OpResult(false, clientName, scriptName, CLIENT_DISCONNECTED);
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

    /**
     * Stops a script on a client, and cancels a start of it queued for when the
     * client is back: a stop the user asks for outranks a start asked for earlier.
     *
     * @param clientName the pipe of a connected client, or the account UUID of
     *                   one that is not connected, whose queued start is then
     *                   all there is to cancel
     */
    @Override
    public OpResult stopScript(String clientName, String scriptName) {
        boolean isCancelled = cancelQueuedStart(clientName, scriptName);
        Connection conn = getClient(clientName);
        if (conn == null) {
            return isCancelled
                    ? new OpResult(true, clientName, scriptName, QUEUED_START_CANCELLED)
                    : new OpResult(false, clientName, scriptName, "client not found");
        }
        if (!conn.isAlive()) {
            return new OpResult(false, clientName, scriptName, CLIENT_DISCONNECTED);
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
            return new OpResult(false, clientName, scriptName, CLIENT_DISCONNECTED);
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

    /** Stops a script on the group's connected members, and cancels its queued start on every member. */
    @Override
    public List<OpResult> stopScriptOnGroup(String groupName, String scriptName) {
        groups().byName(groupName).ifPresent(group ->
                group.members().forEach(uuid -> queue().dequeue(uuid, scriptName)));
        return executeOnGroup(groupName, scriptName, "stop");
    }

    @Override
    public List<OpResult> restartScriptOnGroup(String groupName, String scriptName) {
        return executeOnGroup(groupName, scriptName, "restart");
    }

    /** Stops everything on the group's connected members, and cancels every start queued on its members. */
    @Override
    public List<OpResult> stopAllScriptsOnGroup(String groupName) {
        Optional<ClientGroup> found = groups().byName(groupName);
        if (found.isEmpty()) {
            return List.of(new OpResult(false, groupName, null, "group not found"));
        }
        ClientGroup group = found.get();
        group.members().forEach(queue()::dequeueAll);

        List<OpResult> results = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            conn.getRuntime().stopAll();
            results.add(new OpResult(true, conn.getName(), "*", "stopped all"));
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    /**
     * Stops each script running on the group's connected members, and cancels
     * every start queued on its members, as the user's Stop all on a group
     * does. Unlike {@link #stopAllScriptsOnGroup}, which unloads them, the
     * scripts stay loaded, so each can be run again from where it is listed.
     *
     * @return one result per script stopped, then a warning per member not connected
     */
    public List<OpResult> stopRunningOnGroup(String groupName) {
        Optional<ClientGroup> found = groups().byName(groupName);
        if (found.isEmpty()) {
            return List.of(new OpResult(false, groupName, null, "group not found"));
        }
        ClientGroup group = found.get();
        group.members().forEach(queue()::dequeueAll);
        List<OpResult> results = new ArrayList<>();
        for (Connection conn : getGroupClients(groupName)) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                if (runner.isRunning()) {
                    results.add(stopScript(conn.getName(), runner.getScriptName()));
                }
            }
        }
        addDisconnectedWarnings(group, results);
        return results;
    }

    // ── All-client script operations ────────────────────────────────────────

    @Override
    public List<OpResult> startScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "start");
    }

    /** Stops a script on every connected client, and cancels its queued start on every client. */
    @Override
    public List<OpResult> stopScriptOnAll(String scriptName) {
        queue().dequeueScript(scriptName);
        return executeOnAll(scriptName, "stop");
    }

    @Override
    public List<OpResult> restartScriptOnAll(String scriptName) {
        return executeOnAll(scriptName, "restart");
    }

    /** Stops everything on every connected client, and empties the start-when-back queue. */
    @Override
    public void stopAllScriptsOnAll() {
        queue().clear();
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
        Optional<ClientGroup> group = groups().byName(groupName);
        if (group.isEmpty()) {
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
        addScheduleDisconnectedWarnings(group.get(), results);
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
        Optional<ClientGroup> group = groups().byName(groupName);
        if (group.isEmpty()) {
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
        addDisconnectedWarnings(group.get(), results);
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

    /**
     * One failure per member that was not acted on: each member not connected,
     * named by its account UUID, then each unresolved member, by its pipe.
     */
    private void addDisconnectedWarnings(ClientGroup group, List<OpResult> results) {
        List<Connection> activeClients = getGroupClients(group.name());
        for (String uuid : GroupMembers.offline(group, activeClients)) {
            results.add(new OpResult(false, uuid, null, CLIENT_DISCONNECTED));
        }
        for (String pipe : group.unresolved()) {
            results.add(new OpResult(false, pipe, null, UNKNOWN_CLIENT));
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
            return new ScheduleOpResult(false, clientName, scriptName, null, CLIENT_DISCONNECTED);
        }
        if (conn.getRuntime().findRunner(scriptName) == null) {
            return new ScheduleOpResult(false, clientName, scriptName, null, "script not found");
        }
        return op.apply(conn);
    }

    private List<ScheduleOpResult> scheduleOnGroup(String groupName, String scriptName,
                                                   BiFunction<String, String, ScheduleOpResult> perClient) {
        Optional<ClientGroup> group = groups().byName(groupName);
        if (group.isEmpty()) {
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
        addScheduleDisconnectedWarnings(group.get(), results);
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

    /** As {@link #addDisconnectedWarnings}, for schedule results. */
    private void addScheduleDisconnectedWarnings(ClientGroup group, List<ScheduleOpResult> results) {
        List<Connection> activeClients = getGroupClients(group.name());
        for (String uuid : GroupMembers.offline(group, activeClients)) {
            results.add(new ScheduleOpResult(false, uuid, null, null, CLIENT_DISCONNECTED));
        }
        for (String pipe : group.unresolved()) {
            results.add(new ScheduleOpResult(false, pipe, null, null, UNKNOWN_CLIENT));
        }
    }

    /**
     * The key of the client named {@code client}: a connection's pipe, else an
     * account UUID for a client that is not connected.
     */
    private Optional<ClientKey> keyOfClient(String client) {
        if (getClient(client) != null) {
            return Optional.of(ctx.clientKeyOf(client));
        }
        return AccountReply.identified(client).map(ClientKey::account);
    }

    /** Cancels the start of {@code script} queued for when {@code client} is back; {@code false} if none was. */
    private boolean cancelQueuedStart(String client, String script) {
        return keyOfClient(client)
                .filter(ClientKey::isRemembered)
                .flatMap(ClientKey::accountUuid)
                .map(uuid -> queue().dequeue(uuid, script))
                .orElse(false);
    }

    private GroupStore groups() {
        return ctx.getGroupStore();
    }

    private StartWhenBackQueue queue() {
        return ctx.getStartWhenBackQueue();
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
