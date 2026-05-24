package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;

/**
 * Navigation and pathfinding.
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
     * Plane-aware convenience — equivalent to
     * {@link #walkWorldPathAsync(int, int, int) walkWorldPathAsync(x, y, plane)}.
     *
     * <p>The no-plane convenience that walks to the local player's current
     * plane lives on {@link com.botwithus.bot.api.GameAPI#walkWorldPath(int, int) GameAPI}
     * because it needs {@code getLocalPlayer()}, which isn't part of this
     * navigation surface.</p>
     */
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
}
