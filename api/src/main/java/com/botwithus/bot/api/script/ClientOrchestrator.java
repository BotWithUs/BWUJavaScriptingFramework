package com.botwithus.bot.api.script;

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

    // ── Status ──────────────────────────────────────────────────────────

    /** Returns script status across all clients. */
    List<ScriptStatusEntry> getStatusAll();

    /** Returns script status for clients in a group. */
    List<ScriptStatusEntry> getStatusForGroup(String groupName);

    // ── Result types ────────────────────────────────────────────────────

    /** Result of a script operation on a specific client. */
    record OpResult(boolean success, String clientName, String scriptName, String message) {}

    /** Snapshot of a script's state on a specific client. */
    record ScriptStatusEntry(
            String clientName,
            String scriptName,
            String version,
            boolean running,
            boolean clientAlive
    ) {}
}
