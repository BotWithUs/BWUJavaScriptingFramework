package com.botwithus.bot.core.shm;

/**
 * One row of the projectiles array in a snapshot. See
 * {@link Layout#PROJECTILE_ENTRY_SIZE} for the byte layout.
 *
 * <p>Rows arrive from the producer's projectile-list walk: one per in-flight
 * projectile (a thrown spell/arrow graphic travelling source&rarr;target). The
 * walker emits all live projectiles regardless of plane — consumers filter on
 * the immutable snapshot.</p>
 *
 * @param projectileId graphic (spot-anim) id of the projectile
 * @param startCycle   game cycle the projectile was launched
 * @param endCycle     game cycle it lands
 * @param sourceIndex  server index of the source entity; {@code -1} if tile-anchored
 * @param sourceType   source entity-type tag (raw passthrough from the engine)
 * @param targetIndex  server index of the target entity; {@code -1} if tile target
 * @param targetType   target entity-type tag (raw passthrough from the engine)
 * @param startTileX   absolute world tile X of the launch point
 * @param startTileY   absolute world tile Y of the launch point
 * @param endTileX     absolute world tile X of the target point
 * @param endTileY     absolute world tile Y of the target point
 * @param plane        {@code 0..3}
 */
public record ProjectileEntry(
        int projectileId,
        int startCycle,
        int endCycle,
        int sourceIndex,
        int sourceType,
        int targetIndex,
        int targetType,
        int startTileX,
        int startTileY,
        int endTileX,
        int endTileY,
        int plane
) {
}
