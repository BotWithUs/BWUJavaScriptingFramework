package com.botwithus.bot.api;

import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;

/**
 * High-level navigation interface providing blocking walk operations.
 *
 * <p>Walk methods block the calling thread until the walk completes
 * (arrived, cancelled, failed, or timeout), but do <b>not</b> block
 * the pipe — other threads can still send RPC calls and receive events
 * while a walk is in progress.</p>
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

    // ============================== Cleanup ==============================

    /**
     * Cancels any active walk. Called automatically by the script runtime
     * when a script stops. Scripts may also call this explicitly.
     */
    void cleanup();
}
