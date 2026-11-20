package com.botwithus.bot.api.entities;

/** Something that plays animations: an NPC, a player, or an animated scene object. */
public interface Animated {

    /** {@link #animationId()} while nothing is playing, as the snapshot publishes it. */
    int NO_ANIMATION = -1;

    /** The animation playing now, or {@link #NO_ANIMATION}. */
    int animationId();

    /** Whether any animation is playing. */
    default boolean isAnimating() {
        return animationId() != NO_ANIMATION;
    }
}
