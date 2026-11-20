package com.botwithus.bot.api.entities;

/**
 * Filters for a query over {@link Actor}s (NPCs and players): stance, spot animation,
 * movement and combat level, plus the {@link AnimationFilters animation} ones.
 *
 * @param <T> actor type
 * @param <Q> the query's own type, for chaining
 */
public interface ActorFilters<T extends Actor, Q> extends AnimationFilters<T, Q> {

    /** Actors whose stance animation is any of {@code stanceIds}. */
    default Q withStance(int... stanceIds) {
        int[] ids = stanceIds.clone();
        return filter(t -> EntityFilterSupport.containsId(ids, t.stanceId()));
    }

    /**
     * Actors whose first spot animation is any of {@code spotAnimIds}. Only the first
     * concurrent spot animation is in the snapshot, so a second one playing alongside it is
     * not matched; subscribe to {@code SpotAnimEvent} to see every one.
     */
    default Q withSpotAnim(int... spotAnimIds) {
        int[] ids = spotAnimIds.clone();
        return filter(t -> EntityFilterSupport.containsId(ids, t.spotAnimId()));
    }

    /** Actors walking or running this tick. */
    default Q moving() {
        return filter(Actor::isMoving);
    }

    /** Actors standing still this tick. */
    default Q notMoving() {
        return filter(t -> !t.isMoving());
    }

    /** Actors with a combat level from {@code min} to {@code max}, inclusive. */
    default Q withCombatLevel(int min, int max) {
        return filter(t -> t.combatLevel() >= min && t.combatLevel() <= max);
    }
}
