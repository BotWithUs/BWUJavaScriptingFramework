package com.botwithus.bot.api.entities;

/**
 * A character in the scene, an {@link Npc} or a {@link Player}: it has a position, animates,
 * moves, and has a combat level. What the snapshot publishes for both is here; health is on
 * {@link Npc} only, because the snapshot carries no hitpoints for other players.
 */
public interface Actor extends EntityContext, Animated {

    /** The idle/walk/run stance animation, or {@code -1}. */
    int stanceId();

    /** The first spot animation (graphic) playing on this actor, or {@code -1}. */
    int spotAnimId();

    /** Whether the actor is walking or running this tick. */
    boolean isMoving();

    /** Combat level as the client shows it. */
    int combatLevel();
}
