package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.LocalPlayer;

/**
 * Convenience wrapper around {@link GameAPI#getLocalPlayer()}.
 * Fetches fresh data on every call (no caching).
 */
public final class LocalPlayerHelper {

    private final GameAPI api;

    /**
     * Creates a new local player helper.
     *
     * @param api the game API instance
     */
    public LocalPlayerHelper(GameAPI api) {
        this.api = api;
    }

    private LocalPlayer lp() {
        return api.getLocalPlayer();
    }

    /**
     * Checks whether the local player is currently playing an animation.
     *
     * @return {@code true} if animating
     */
    public boolean isAnimating() {
        return lp().animationId() != -1;
    }

    /**
     * Checks whether the local player is currently moving.
     *
     * @return {@code true} if moving
     */
    public boolean isMoving() {
        return lp().isMoving();
    }

    /**
     * Checks whether the local player is currently targeting another entity.
     *
     * @return {@code true} if in combat
     */
    public boolean isInCombat() {
        return lp().targetIndex() != -1;
    }

    /**
     * Returns the local player's current health.
     *
     * @return current health points
     */
    public int getHealth() {
        return lp().health();
    }

    /**
     * Returns the local player's maximum health.
     *
     * @return maximum health points
     */
    public int getMaxHealth() {
        return lp().maxHealth();
    }

    /**
     * Returns health as a fraction in [0.0, 1.0]. Returns 1.0 if health data is unavailable.
     *
     * @return health percentage
     */
    public double getHealthPercent() {
        LocalPlayer p = lp();
        if (p.maxHealth() <= 0) return 1.0;
        return (double) p.health() / p.maxHealth();
    }

    /**
     * Returns the local player's combat level.
     *
     * @return the combat level
     */
    public int getCombatLevel() {
        return lp().combatLevel();
    }

    /**
     * Returns the local player's tile X coordinate.
     *
     * @return the tile X coordinate
     */
    public int getTileX() {
        return lp().tileX();
    }

    /**
     * Returns the local player's tile Y coordinate.
     *
     * @return the tile Y coordinate
     */
    public int getTileY() {
        return lp().tileY();
    }

    /**
     * Returns the local player's plane (height level).
     *
     * @return the plane (0-3)
     */
    public int getPlane() {
        return lp().plane();
    }

    /**
     * Returns the local player's display name.
     *
     * @return the player name
     */
    public String getName() {
        return lp().name();
    }

    /**
     * Returns the local player's current animation ID.
     *
     * @return the animation ID, or {@code -1} if idle
     */
    public int getAnimationId() {
        return lp().animationId();
    }

    /**
     * Calculates the Chebyshev (tile) distance to the given coordinates.
     *
     * @param tileX the target tile X coordinate
     * @param tileY the target tile Y coordinate
     * @return the distance in tiles
     */
    public int distanceTo(int tileX, int tileY) {
        LocalPlayer p = lp();
        return Math.max(Math.abs(p.tileX() - tileX), Math.abs(p.tileY() - tileY));
    }

    /**
     * Checks whether the local player is idle (not animating, not moving, not in combat).
     *
     * @return {@code true} if idle
     */
    public boolean isIdle() {
        LocalPlayer p = lp();
        return p.animationId() == -1 && !p.isMoving() && p.targetIndex() == -1;
    }
}
