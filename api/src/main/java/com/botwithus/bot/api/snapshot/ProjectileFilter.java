package com.botwithus.bot.api.snapshot;

import java.util.function.Predicate;

/**
 * Predicate for filtering {@link Projectile} entries in a {@link GameSnapshot}.
 * Compose with {@link Predicate#and(Predicate)} / {@link Predicate#or(Predicate)}.
 */
@FunctionalInterface
public interface ProjectileFilter extends Predicate<Projectile> {

    @Override
    default ProjectileFilter and(Predicate<? super Projectile> other) {
        return p -> test(p) && other.test(p);
    }

    @Override
    default ProjectileFilter or(Predicate<? super Projectile> other) {
        return p -> test(p) || other.test(p);
    }

    @Override
    default ProjectileFilter negate() {
        return p -> !test(p);
    }

    static ProjectileFilter id(int projectileId) {
        return p -> p.projectileId() == projectileId;
    }

    static ProjectileFilter onPlane(int plane) {
        return p -> p.plane() == plane;
    }

    /** Projectiles launched by the given source entity (NPC/player server index). */
    static ProjectileFilter fromSource(int sourceIndex) {
        return p -> p.sourceIndex() == sourceIndex;
    }

    /** Projectiles aimed at the given target entity (NPC/player server index). */
    static ProjectileFilter toTarget(int targetIndex) {
        return p -> p.targetIndex() == targetIndex;
    }

    // Spatial predicates are named for which end they match. A projectile has
    // two positions, so an undifferentiated "atTile" (as on GroundItemFilter)
    // would be ambiguous here.

    /** Projectiles that will land on the given tile. */
    static ProjectileFilter landingAt(int tileX, int tileY) {
        return p -> p.endTileX() == tileX && p.endTileY() == tileY;
    }

    /** Projectiles that will land on the given tile and plane. */
    static ProjectileFilter landingAt(int tileX, int tileY, int plane) {
        return p -> p.endTileX() == tileX && p.endTileY() == tileY && p.plane() == plane;
    }

    /** Projectiles launched from the given tile. */
    static ProjectileFilter launchedAt(int tileX, int tileY) {
        return p -> p.startTileX() == tileX && p.startTileY() == tileY;
    }

    /** Projectiles anchored to a fixed tile at the source end rather than an entity. */
    static ProjectileFilter fromTile() {
        return p -> p.sourceIndex() < 0;
    }

    /** Projectiles aimed at a fixed tile rather than an entity. */
    static ProjectileFilter toTile() {
        return p -> p.targetIndex() < 0;
    }

    /**
     * Projectiles whose flight brackets the given game cycle — i.e. actually in
     * the air at that moment. Pass the cycle from {@code GameAPI.getGameCycle()};
     * see {@code entities.Projectile.flightProgress(int)} for why the cycle is a
     * parameter rather than read internally.
     */
    static ProjectileFilter inFlightAt(int gameCycle) {
        return p -> gameCycle >= p.startCycle() && gameCycle <= p.endCycle();
    }
}
