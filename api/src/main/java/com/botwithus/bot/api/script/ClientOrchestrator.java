package com.botwithus.bot.api.script;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-client script lifecycle and group management.
 *
 * <p>Provides operations to start/stop scripts on individual clients,
 * across named groups, or on every connected client. Groups can be
 * created with descriptions (e.g. "Skillers", "Combat") and used to
 * target bulk operations.
 *
 * <p>Obtain via {@link ManagementContext#getOrchestrator()}.
 */
public interface ClientOrchestrator {

    // ── Client queries ──────────────────────────────────────────────────

    /** Returns the names of all connected clients. */
    List<String> getClientNames();

    /** Returns {@code true} if the named client is connected and alive. */
    boolean isClientAlive(String name);

    // ── Group management ────────────────────────────────────────────────

    /** Creates a group. Returns {@code true} if newly created. */
    boolean createGroup(String name, String description);

    /** Creates a group with no description. */
    default boolean createGroup(String name) { return createGroup(name, null); }

    /** Deletes a group. Returns {@code true} if it existed. */
    boolean deleteGroup(String name);

    /** Returns the names of all groups. */
    Set<String> getGroupNames();

    /** Returns the description of a group, or {@code null}. */
    String getGroupDescription(String groupName);

    /** Sets the description of a group. */
    void setGroupDescription(String groupName, String description);

    /** Returns the client names in a group. */
    Set<String> getGroupMembers(String groupName);

    /** Adds a client to a group. */
    boolean addToGroup(String groupName, String clientName);

    /** Removes a client from a group. */
    boolean removeFromGroup(String groupName, String clientName);

    // ── Single-client script operations ─────────────────────────────────

    /** Starts a script on a specific client. */
    OpResult startScript(String clientName, String scriptName);

    /** Stops a script on a specific client. */
    OpResult stopScript(String clientName, String scriptName);

    /** Restarts a script on a specific client. */
    OpResult restartScript(String clientName, String scriptName);

    // ── Group script operations ─────────────────────────────────────────

    /** Starts a script on all clients in a group. */
    List<OpResult> startScriptOnGroup(String groupName, String scriptName);

    /** Stops a script on all clients in a group. */
    List<OpResult> stopScriptOnGroup(String groupName, String scriptName);

    /** Restarts a script on all clients in a group. */
    List<OpResult> restartScriptOnGroup(String groupName, String scriptName);

    /** Stops all scripts on all clients in a group. */
    List<OpResult> stopAllScriptsOnGroup(String groupName);

    // ── All-client script operations ────────────────────────────────────

    /** Starts a script on every connected client. */
    List<OpResult> startScriptOnAll(String scriptName);

    /** Stops a script on every connected client. */
    List<OpResult> stopScriptOnAll(String scriptName);

    /** Restarts a script on every connected client. */
    List<OpResult> restartScriptOnAll(String scriptName);

    /** Stops all scripts on every connected client. */
    void stopAllScriptsOnAll();

    // ── Single-client schedule operations ───────────────────────────────

    /** Schedules a one-shot start of a script after {@code delay}. */
    ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay);

    /** Schedules a one-shot start of a script after {@code delay}, applying {@code config} before start. */
    ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay, Map<String, Object> config);

    /** Schedules a one-shot start of a script at the given instant. */
    ScheduleOpResult scheduleScriptAt(String clientName, String scriptName, Instant at);

    /** Schedules a script to repeat on a fixed interval. */
    ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval);

    /** Schedules a script to repeat on a fixed interval, auto-stopping after {@code maxDuration} each cycle. */
    ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval, Duration maxDuration);

    // ── Group schedule operations ───────────────────────────────────────

    /** Schedules a one-shot start of a script on every client in a group. */
    List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay);

    /** Schedules a one-shot start of a script on every client in a group, applying {@code config} before start. */
    List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay, Map<String, Object> config);

    /** Schedules a one-shot start at the given instant on every client in a group. */
    List<ScheduleOpResult> scheduleScriptOnGroupAt(String groupName, String scriptName, Instant at);

    /** Schedules a recurring script on every client in a group. */
    List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName, Duration interval);

    /** Schedules a recurring script on every client in a group with an auto-stop after {@code maxDuration} each cycle. */
    List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName, Duration interval, Duration maxDuration);

    // ── All-client schedule operations ──────────────────────────────────

    /** Schedules a one-shot start of a script on every connected client. */
    List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay);

    /** Schedules a one-shot start of a script on every connected client, applying {@code config} before start. */
    List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay, Map<String, Object> config);

    /** Schedules a one-shot start at the given instant on every connected client. */
    List<ScheduleOpResult> scheduleScriptOnAllAt(String scriptName, Instant at);

    /** Schedules a recurring script on every connected client. */
    List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval);

    /** Schedules a recurring script on every connected client with an auto-stop after {@code maxDuration} each cycle. */
    List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval, Duration maxDuration);

    // ── Schedule cancellation ───────────────────────────────────────────

    /** Cancels a single schedule on the named client. */
    boolean cancelSchedule(String clientName, String scheduleId);

    /** Cancels every schedule on every client in a group; one result per cancellation. */
    List<ScheduleOpResult> cancelAllSchedulesOnGroup(String groupName);

    /** Cancels every schedule on every connected client. */
    void cancelAllSchedules();

    // ── Schedule queries ────────────────────────────────────────────────

    /** Returns every active schedule across all connected clients. */
    List<ScheduledScriptEntry> listScheduled();

    /** Returns active schedules on the named client. */
    List<ScheduledScriptEntry> listScheduledForClient(String clientName);

    /** Returns active schedules across all clients in a group. */
    List<ScheduledScriptEntry> listScheduledForGroup(String groupName);

    // ── Status ──────────────────────────────────────────────────────────

    /** Returns script status across all clients. */
    List<ScriptStatusEntry> getStatusAll();

    /** Returns script status for clients in a group. */
    List<ScriptStatusEntry> getStatusForGroup(String groupName);

    // ── Result types ────────────────────────────────────────────────────

    /** Result of a script operation on a specific client. */
    record OpResult(boolean success, String clientName, String scriptName, String message) {}

    /**
     * Result of a schedule operation. {@code scheduleId} is non-null on
     * successful schedule creations and on cancellations of an identified
     * schedule; null otherwise.
     */
    record ScheduleOpResult(
            boolean success,
            String clientName,
            String scriptName,
            String scheduleId,
            String message
    ) {}

    /** A single active schedule pinned to a specific client. */
    record ScheduledScriptEntry(
            String clientName,
            String scheduleId,
            String scriptName,
            Instant nextRun,
            Duration interval,
            Duration maxDuration
    ) {}

    /** Snapshot of a script's state on a specific client. */
    record ScriptStatusEntry(
            String clientName,
            String scriptName,
            String version,
            boolean running,
            boolean clientAlive
    ) {}
}
