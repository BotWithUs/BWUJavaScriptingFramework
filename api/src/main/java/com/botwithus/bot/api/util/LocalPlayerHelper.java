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

    public boolean isAnimating() {
        return lp().animationId() != -1;
    }

    public boolean isMoving() {
        return lp().isMoving();
    }

    public boolean isInCombat() {
        return lp().targetIndex() != -1;
    }

    public int getHealth() {
        return lp().health();
    }

    public int getMaxHealth() {
        return lp().maxHealth();
    }

    /** Returns health as a fraction in [0.0, 1.0]. */
    public double getHealthPercent() {
        LocalPlayer p = lp();
        if (p.maxHealth() <= 0) return 0.0;
        return (double) p.health() / p.maxHealth();
    }

    public int getCombatLevel() {
        return lp().combatLevel();
    }

    public int getTileX() {
        return lp().tileX();
    }

    public int getTileY() {
        return lp().tileY();
    }

    public int getPlane() {
        return lp().plane();
    }

    public String getName() {
        return lp().name();
    }

    /** Returns -1 if idle. */
    public int getAnimationId() {
        return lp().animationId();
    }

    /** Chebyshev (tile) distance to the given coordinates. */
    public int distanceTo(int tileX, int tileY) {
        LocalPlayer p = lp();
        return Math.max(Math.abs(p.tileX() - tileX), Math.abs(p.tileY() - tileY));
    }

    /** Not animating, not moving, not in combat. */
    public boolean isIdle() {
        LocalPlayer p = lp();
        return p.animationId() == -1 && !p.isMoving() && p.targetIndex() == -1;
    }
}
