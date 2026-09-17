package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one in-flight projectile at the producer's current tick (v17+).
 *
 * <p>Rows come from the producer's projectile-list walk; every active
 * projectile (a thrown spell/arrow graphic travelling source&rarr;target) is
 * emitted. Consumers filter on the immutable snapshot.</p>
 *
 * <p>An endpoint is either an entity or a fixed tile: when {@link #sourceIndex()}
 * (resp. {@link #targetIndex()}) is {@code -1} the corresponding end is a tile,
 * given by {@link #startTileX()}/{@link #startTileY()} (resp.
 * {@link #endTileX()}/{@link #endTileY()}). {@link #sourceType()} /
 * {@link #targetType()} are the engine's raw entity-type tags, surfaced
 * unmodified for the consumer to interpret.</p>
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
public record Projectile(
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
