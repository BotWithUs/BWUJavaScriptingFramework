package com.botwithus.bot.api;

import com.botwithus.bot.api.model.*;

import java.util.List;

/**
 * High-level navigation interface providing blocking walk operations,
 * navigation link management, teleport registration, and automatic cleanup.
 *
 * <p>Walk methods block the calling thread until the walk completes
 * (arrived, cancelled, failed, or timeout), but do <b>not</b> block
 * the pipe — other threads can still send RPC calls and receive events
 * while a walk is in progress.</p>
 *
 * <p>All link and teleport modifications made through this interface are
 * tracked. Call {@link #cleanup()} (or let the runtime call it when the
 * script stops) to undo every modification in reverse order.</p>
 *
 * <p>Obtain an instance through {@link ScriptContext#getNavigation()}.</p>
 *
 * @see GameAPI#walkToAsync(int, int)
 * @see GameAPI#walkWorldPathAsync(int, int, int)
 */
public interface Navigation {

    // ============================== Blocking Walks ==============================

    /**
     * Walks to a tile using local A* pathfinding and blocks until completion.
     *
     * @param x target world tile X
     * @param y target world tile Y
     * @return the walk result
     */
    WalkResult walkTo(int x, int y);

    /**
     * Walks to a tile using local A* pathfinding and blocks until completion or timeout.
     *
     * @param x         target world tile X
     * @param y         target world tile Y
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the walk result
     */
    WalkResult walkTo(int x, int y, long timeoutMs);

    /**
     * Walks a world path (with teleport/door/shortcut support) and blocks until completion.
     *
     * @param x     target world tile X
     * @param y     target world tile Y
     * @param plane target plane
     * @return the walk result
     */
    WalkResult walkWorldPath(int x, int y, int plane);

    /**
     * Walks a world path and blocks until completion or timeout.
     *
     * @param x         target world tile X
     * @param y         target world tile Y
     * @param plane     target plane
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the walk result
     */
    WalkResult walkWorldPath(int x, int y, int plane, long timeoutMs);

    /**
     * Walks a world path on plane 0 and blocks until completion.
     *
     * @param x target world tile X
     * @param y target world tile Y
     * @return the walk result
     */
    default WalkResult walkWorldPath(int x, int y) {
        return walkWorldPath(x, y, 0);
    }

    /**
     * Walks a world path with exact destination tile control and blocks until completion.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane
     * @param exactDestTile if {@code true}, click the exact destination tile with no variance
     * @return the walk result
     */
    default WalkResult walkWorldPath(int x, int y, int plane, boolean exactDestTile) {
        return walkWorldPath(x, y, plane, exactDestTile, null);
    }

    /**
     * Walks a world path with full pathfinder configuration and blocks until completion.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane
     * @param exactDestTile if {@code true}, click the exact destination tile with no variance
     * @param config        pathfinder config overrides, or {@code null} for defaults
     * @return the walk result
     */
    WalkResult walkWorldPath(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config);

    /**
     * Walks a world path with full pathfinder configuration and blocks until completion or timeout.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane
     * @param exactDestTile if {@code true}, click the exact destination tile with no variance
     * @param config        pathfinder config overrides, or {@code null} for defaults
     * @param timeoutMs     maximum time to wait in milliseconds
     * @return the walk result
     */
    WalkResult walkWorldPath(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config, long timeoutMs);

    // ============================== Walk Control ==============================

    /**
     * Cancels any active walk. Emits {@code walk_cancelled} if a walk was in progress.
     */
    void cancelWalk();

    /**
     * Returns the current walker state.
     *
     * @return the walk status
     */
    WalkStatus getWalkStatus();

    // ============================== Path Queries ==============================

    /**
     * Checks if a tile is reachable from the player's current position via local A*.
     *
     * @param x target world X
     * @param y target world Y
     * @return {@code true} if the tile is reachable
     */
    boolean isReachable(int x, int y);

    /**
     * Checks if a tile is reachable with a custom iteration limit.
     *
     * @param x             target world X
     * @param y             target world Y
     * @param maxIterations max A* iterations
     * @return {@code true} if the tile is reachable
     */
    boolean isReachable(int x, int y, int maxIterations);

    /**
     * Finds a local A* path from the player's position to a destination.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findPath(int toX, int toY);

    /**
     * Finds a local A* path between two tiles.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findPath(int fromX, int fromY, int toX, int toY);

    /**
     * Finds a world-scale path from the player's position without walking it.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int toX, int toY);

    /**
     * Finds a world-scale path between two tiles without walking it.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int fromX, int fromY, int toX, int toY);

    // ============================== Nav Link Management ==============================

    /**
     * Adds a transport link to the navigation graph. Tracked for {@link #cleanup()}.
     *
     * @param transport the transport link to add
     */
    void addTransport(NavTransport transport);

    /**
     * Removes a transport link from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void removeTransport(int objectId, int x, int y, int plane);

    /** Removes a transport link on plane 0. */
    default void removeTransport(int objectId, int x, int y) {
        removeTransport(objectId, x, y, 0);
    }

    /**
     * Lists all transport links in the navigation graph.
     *
     * @return a list of transport links
     */
    List<NavTransport> listTransports();

    /**
     * Adds a door to the navigation graph. Tracked for {@link #cleanup()}.
     *
     * @param door the door to add
     */
    void addDoor(NavDoor door);

    /**
     * Removes a door from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void removeDoor(int objectId, int x, int y, int plane);

    /** Removes a door on plane 0. */
    default void removeDoor(int objectId, int x, int y) {
        removeDoor(objectId, x, y, 0);
    }

    /**
     * Lists all doors in the navigation graph.
     *
     * @return a list of doors
     */
    List<NavDoor> listDoors();

    /**
     * Adds an agility shortcut to the navigation graph. Tracked for {@link #cleanup()}.
     *
     * @param shortcut the shortcut to add
     */
    void addShortcut(NavShortcut shortcut);

    /**
     * Removes a shortcut from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void removeShortcut(int objectId, int x, int y, int plane);

    /** Removes a shortcut on plane 0. */
    default void removeShortcut(int objectId, int x, int y) {
        removeShortcut(objectId, x, y, 0);
    }

    /**
     * Adds a plane (floor level) transition. Tracked for {@link #cleanup()}.
     *
     * @param transition the plane transition to add
     */
    void addPlaneTransition(NavPlaneTransition transition);

    /**
     * Removes a plane transition from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void removePlaneTransition(int objectId, int x, int y, int plane);

    /** Removes a plane transition on plane 0. */
    default void removePlaneTransition(int objectId, int x, int y) {
        removePlaneTransition(objectId, x, y, 0);
    }

    /**
     * Adds a climbover obstacle. Tracked for {@link #cleanup()}.
     *
     * @param climbover the climbover to add
     */
    void addClimbover(NavClimbover climbover);

    /**
     * Removes a climbover from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void removeClimbover(int objectId, int x, int y, int plane);

    /** Removes a climbover on plane 0. */
    default void removeClimbover(int objectId, int x, int y) {
        removeClimbover(objectId, x, y, 0);
    }

    // ============================== Batch Link Operations ==============================

    /**
     * Batch-adds transport links to the navigation graph.
     * Not tracked for cleanup — use {@link #addTransport} individually for cleanup tracking.
     *
     * @param links the transport links to add
     * @return the number of links added
     */
    int loadLinksJson(List<NavTransport> links);

    /**
     * Saves all navigation overrides (doors, shortcuts, transports, etc.) to a binary file.
     *
     * @param path output file path, or {@code null} for default {@code "nav_overrides.bin"}
     */
    void saveLinks(String path);

    /**
     * Loads navigation overrides from a binary file.
     *
     * @param path input file path, or {@code null} for default {@code "nav_overrides.bin"}
     * @return the number of links loaded
     */
    int loadLinks(String path);

    // ============================== Teleports ==============================

    /**
     * Registers custom teleports for the pathfinder using the {@code "item_teleports"} format.
     * Tracked for {@link #cleanup()} — calling cleanup will remove all script-registered teleports.
     *
     * @param json JSON string containing teleport definitions
     * @return the number of teleports added
     */
    default int registerTeleports(String json) {
        return registerTeleports(json, "item_teleports");
    }

    /**
     * Registers custom teleports for the pathfinder.
     * Tracked for {@link #cleanup()} — calling cleanup will remove all script-registered teleports.
     *
     * @param json   JSON string containing teleport definitions
     * @param format format: {@code "item_teleports"} or {@code "gibson"}
     * @return the number of teleports added
     */
    int registerTeleports(String json, String format);

    /**
     * Removes all teleports registered via {@link #registerTeleports}.
     * Built-in teleports (loaded from embedded resources) are preserved.
     *
     * @return the number of teleports removed
     */
    int clearScriptTeleports();

    /**
     * Lists registered teleports.
     *
     * @param scriptOnly if {@code true}, only list script-registered teleports
     * @return a list of teleports
     */
    List<NavTeleport> listTeleports(boolean scriptOnly);

    // ============================== Region Cache ==============================

    /**
     * Returns the region collision cache size.
     *
     * @return the number of cached regions
     */
    int getRegionCacheSize();

    /**
     * Invalidates all cached region collision data.
     */
    void clearRegionCache();

    // ============================== Stats ==============================

    /**
     * Returns navigation data statistics.
     *
     * @return the nav stats
     */
    NavStats getNavStats();

    // ============================== Cleanup ==============================

    /**
     * Undoes all navigation modifications made through this instance.
     *
     * <p>In reverse order: removes all added links (transports, doors,
     * shortcuts, plane transitions, climbovers), clears script-registered
     * teleports, and cancels any active walk.</p>
     *
     * <p>Called automatically by the script runtime when a script stops.
     * Scripts may also call this explicitly to reset their navigation state.</p>
     */
    void cleanup();
}
