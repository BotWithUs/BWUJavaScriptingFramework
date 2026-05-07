package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.*;

import java.util.List;

/**
 * Navigation, pathfinding, and navigation link management.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface NavigationAPI {

    // ============================== Async Walks ==============================

    /**
     * Starts a local A* walk to the given tile. Returns immediately.
     *
     * @param x target world tile X
     * @param y target world tile Y
     */
    void walkToAsync(int x, int y);

    /**
     * Starts a world-scale walk using HPA&#42;/flat A&#42; with teleport, door,
     * shortcut, and transport support. Returns immediately.
     *
     * @param x     target world tile X
     * @param y     target world tile Y
     * @param plane target plane (height level)
     */
    void walkWorldPathAsync(int x, int y, int plane);

    /**
     * Convenience overload — walk to the local player's current plane.
     * Equivalent to {@code walkWorldPathAsync(x, y, lp.plane())}, falling
     * back to plane 0 when the player isn't in-game.
     */
    default void walkWorldPath(int x, int y) {
        com.botwithus.bot.api.snapshot.LocalPlayer lp = null;
        if (this instanceof com.botwithus.bot.api.GameAPI api) {
            lp = api.getLocalPlayer();
        }
        walkWorldPathAsync(x, y, lp == null ? 0 : lp.plane());
    }

    /** Plane-aware overload of {@link #walkWorldPath(int, int)}. */
    default void walkWorldPath(int x, int y, int plane) {
        walkWorldPathAsync(x, y, plane);
    }

    /**
     * Starts a world-scale walk with exact destination tile control.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane (height level)
     * @param exactDestTile if {@code true}, click the exact destination tile
     */
    default void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile) {
        walkWorldPathAsync(x, y, plane, exactDestTile, null);
    }

    /**
     * Starts a world-scale walk with full pathfinder configuration.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane (height level)
     * @param exactDestTile if {@code true}, click the exact destination tile
     * @param config        pathfinder config overrides, or {@code null} for defaults
     */
    void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config);

    /**
     * Cancels any active walk.
     */
    void walkCancel();

    /**
     * Returns the current walker state.
     *
     * @return the walk status
     */
    WalkStatus getWalkStatus();

    // ============================== Path Queries ==============================

    /**
     * Checks if a tile is reachable from the player's current position.
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
     * Finds a local A* path between the player and a destination.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findPath(int toX, int toY);

    /**
     * Finds a local A* path from a specific origin.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findPath(int fromX, int fromY, int toX, int toY);

    /**
     * Finds a world-scale path without walking it.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int toX, int toY);

    /**
     * Finds a world-scale path from a specific origin without walking it.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int fromX, int fromY, int toX, int toY);

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

    // ============================== Navigation Links ==============================

    void navAddTransport(NavTransport transport);
    void navRemoveTransport(int objectId, int x, int y, int plane);
    List<NavTransport> navListTransports();

    void navAddDoor(NavDoor door);
    void navRemoveDoor(int objectId, int x, int y, int plane);
    List<NavDoor> navListDoors();

    void navAddShortcut(NavShortcut shortcut);
    void navRemoveShortcut(int objectId, int x, int y, int plane);

    void navAddPlaneTransition(NavPlaneTransition transition);
    void navRemovePlaneTransition(int objectId, int x, int y, int plane);

    void navAddClimbover(NavClimbover climbover);
    void navRemoveClimbover(int objectId, int x, int y, int plane);

    int navLoadJson(List<NavTransport> links);
    void navSaveLinks(String path);
    int navLoadLinks(String path);
    NavStats navGetStats();

    // ============================== Teleports ==============================

    int navRegisterTeleports(String json, String format);
    int navClearScriptTeleports();
    List<NavTeleport> navListTeleports(boolean scriptOnly);
}
