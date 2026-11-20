package com.botwithus.bot.api.entities;

import java.util.function.Predicate;

/**
 * Animation filters for a query over {@link Animated} entities. Mixed into the query types
 * that have animation data ({@link Npcs.Query}, {@link Players.Query},
 * {@link SceneObjects.Query}); every method narrows through {@link #filter}.
 *
 * @param <T> entity type
 * @param <Q> the query's own type, for chaining
 */
public interface AnimationFilters<T extends Animated, Q> {

    /** Adds a predicate; implemented by {@link EntityQuery#filter}. */
    Q filter(Predicate<T> predicate);

    /** Entities playing any animation. */
    default Q animating() {
        return filter(Animated::isAnimating);
    }

    /** Entities playing no animation. */
    default Q notAnimating() {
        return filter(t -> !t.isAnimating());
    }

    /** Entities playing any of {@code animationIds}. */
    default Q withAnimation(int... animationIds) {
        int[] ids = animationIds.clone();
        return filter(t -> EntityFilterSupport.containsId(ids, t.animationId()));
    }
}
