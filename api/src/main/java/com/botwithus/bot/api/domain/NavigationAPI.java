package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;

/**
 * Navigation and pathfinding.
 *
 * <h2>One script walks at a time</h2>
 *
 * <p>A client has one character, one position, and one server-side action queue
 * that drains a single action per server tick. Two scripts walking the same
 * character therefore cannot both make progress: their per-tile clicks
 * interleave, neither sees the character move toward its own goal, and both
 * re-plan forever from a position neither predicted. So the character is held
 * by <b>one walker at a time</b>:</p>
 *
 * <ul>
 *   <li>A walk request from a script while <em>another</em> script is walking
 *       is <b>refused</b>. It returns immediately without starting anything,
 *       the walk already in progress is left alone, and
 *       {@link #getWalkStatus()} reports {@code "refused_busy"} to the script
 *       that was refused. It does not queue and it will not be retried for you
 *       — decide in your own script whether to wait and ask again.</li>
 *   <li>Re-targeting your <em>own</em> walk is always allowed: the walk in
 *       flight is cancelled and joined, then the new one starts.</li>
 *   <li>{@link #walkCancel()} only cancels a walk you started. Asking to cancel
 *       somebody else's is a no-op, not an error.</li>
 *   <li>{@link #getWalkStatus()} is scoped to the calling script: it reports
 *       your walk, or the outcome of your last one — never a sibling
 *       script's.</li>
 *   <li>The <b>host</b>, and any {@code ManagementScript} orchestrating this
 *       client, walk with override authority: they may take the character from
 *       a script and cancel any walk. This is deliberate — an orchestrator that
 *       could not move a character it manages could not do its job.</li>
 * </ul>
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface NavigationAPI {

    // ============================== Async Walks ==============================

    /**
     * Starts a local A* walk to the given tile. Returns immediately.
     *
     * <p>Refused, silently and without starting a walk, if another script is
     * already walking this character — check {@link #getWalkStatus()} for
     * {@code "refused_busy"}.</p>
     *
     * @param x target world tile X
     * @param y target world tile Y
     */
    void walkToAsync(int x, int y);

    /**
     * Starts a world-scale walk using HPA&#42;/flat A&#42; with teleport, door,
     * shortcut, and transport support. Returns immediately.
     *
     * <p>Refused, silently and without starting a walk, if another script is
     * already walking this character — check {@link #getWalkStatus()} for
     * {@code "refused_busy"}.</p>
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
     * <p>Refused, silently and without starting a walk, if another script is
     * already walking this character — check {@link #getWalkStatus()} for
     * {@code "refused_busy"}.</p>
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane (height level)
     * @param exactDestTile if {@code true}, click the exact destination tile
     * @param config        pathfinder config overrides, or {@code null} for defaults
     */
    void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config);

    /**
     * Cancels the walk this script started. A no-op if the walk in progress
     * belongs to another script; the host and management scripts may cancel
     * any walk.
     */
    void walkCancel();

    /**
     * Returns the walker state <em>for the calling script</em>: {@code walking}
     * while its own walk is in flight, otherwise the outcome of its last
     * request — {@code arrived}, {@code cancelled}, {@code failed},
     * {@code refused_busy}, or {@code idle} if it has never asked for one.
     *
     * <p>Never reports a sibling script's walk. The host and management scripts
     * see whichever walk is in progress, whoever started it.</p>
     *
     * <p>This is a <em>level</em>, so it cannot on its own tell you whether the
     * request you just made was the one refused — an older refusal reads the
     * same. Use {@link #walkRefusalCount()} for that.</p>
     *
     * @return the walk status
     */
    WalkStatus getWalkStatus();

    /**
     * How many of the calling script's walk requests have been refused because
     * another script held the character.
     *
     * <p>Monotonic and caller-scoped. Read it before a walk request and again
     * after: an increase means <em>that</em> request was refused. This is the
     * reliable way to detect a refusal — {@link #getWalkStatus()} reports a
     * level, so a refusal left over from an earlier request is indistinguishable
     * from one caused by this one, and a host or management caller sees
     * {@code walking} for whatever walk is in progress rather than its own
     * refusal at all.</p>
     *
     * <p>Implementations that do not track refusals return {@code 0}, which
     * simply reads as "never refused".</p>
     *
     * @return the refusal count for the calling script, never negative
     */
    default long walkRefusalCount() {
        return 0L;
    }

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
