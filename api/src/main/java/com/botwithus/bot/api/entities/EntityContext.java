package com.botwithus.bot.api.entities;

/**
 * Shared surface for everything an {@link EntityQuery} can reason about
 * without consulting an entity-specific definition. Position is the common
 * denominator: it's needed for {@code withinDistance} and {@code nearest}
 * sorting on every entity type, and it lives on the snapshot record so we
 * can read it without any RPC.
 *
 * <p>Implementations are the rich wrapper classes ({@link Npc}, {@link Player},
 * future {@code SceneObject}, {@code GroundItem}) — the snapshot records
 * themselves don't implement this; they're the data we wrap.</p>
 */
public interface EntityContext {

    /** Absolute world tile X. */
    int tileX();

    /** Absolute world tile Y. */
    int tileY();

    /** Plane / height level (0-3). */
    int plane();

    /**
     * Chebyshev (king-move) distance to another tile — same metric the game
     * itself uses for "within range" checks on actions.
     */
    default int distanceTo(int x, int y) {
        return Math.max(Math.abs(tileX() - x), Math.abs(tileY() - y));
    }

    /** Distance to another entity. */
    default int distanceTo(EntityContext other) {
        return distanceTo(other.tileX(), other.tileY());
    }
}
