package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.LocalPlayer;

/**
 * Convenience wrapper around {@link GameAPI#getLocalPlayer()}.
 * Fetches fresh data on every call (no caching).
 */
public final class LocalPlayerHelper {

    private final GameAPI api;

    public LocalPlayerHelper(GameAPI api) {
        this.api = api;
    }

    private LocalPlayer lp() {
        return api.getLocalPlayer();
    }

    /**
     * @return {@code true} if the player has a non-idle animation
     */
    public boolean isAnimating() {
        return lp().animationId() != -1;
    }

    /**
     * @return {@code true} if the player is currently moving
     */
    public boolean isMoving() {
        return lp().isMoving();
    }

    /**
     * @return {@code true} if the player is targeting another entity (in combat)
     */
    public boolean isInCombat() {
        return lp().targetIndex() != -1;
    }

    /**
     * @return the player's current health
     */
    public int getHealth() {
        return lp().health();
    }

    /**
     * @return the player's maximum health
     */
    public int getMaxHealth() {
        return lp().maxHealth();
    }

    /**
     * @return health as a fraction in [0.0, 1.0]
     */
    public double getHealthPercent() {
        LocalPlayer p = lp();
        if (p.maxHealth() <= 0) return 0.0;
        return (double) p.health() / p.maxHealth();
    }

    /**
     * @return the player's combat level
     */
    public int getCombatLevel() {
        return lp().combatLevel();
    }

    /**
     * @return the player's current tile X coordinate
     */
    public int getTileX() {
        return lp().tileX();
    }

    /**
     * @return the player's current tile Y coordinate
     */
    public int getTileY() {
        return lp().tileY();
    }

    /**
     * @return the player's current plane
     */
    public int getPlane() {
        return lp().plane();
    }

    /**
     * @return the player's display name
     */
    public String getName() {
        return lp().name();
    }

    /**
     * @return the player's current animation ID, or -1 if idle
     */
    public int getAnimationId() {
        return lp().animationId();
    }

    /**
     * Calculates the Chebyshev distance (tile distance) to the given coordinates.
     *
     * @param tileX target tile X
     * @param tileY target tile Y
     * @return the distance in tiles
     */
    public int distanceTo(int tileX, int tileY) {
        LocalPlayer p = lp();
        return Math.max(Math.abs(p.tileX() - tileX), Math.abs(p.tileY() - tileY));
    }

    /**
     * @return {@code true} if the player is idle (not animating, not moving, not in combat)
     */
    public boolean isIdle() {
        LocalPlayer p = lp();
        return p.animationId() == -1 && !p.isMoving() && p.targetIndex() == -1;
    }
}
