package com.botwithus.bot.core.worldwalker;

/**
 * Acceptance set for a route query / executor run. The goal is satisfied when
 * the player's tile lies within a Chebyshev {@code radius} around
 * {@code (x, y, plane)} on the same plane. {@code radius == 0} demands the
 * exact tile; a negative radius is normalised to 0 at the ABI boundary.
 *
 * <p>Note: the query surface ({@link WorldWalker#query}) currently plans to
 * the exact {@code (x, y, plane)} tile and ignores {@code radius}; the field
 * is honoured by the executor and reserved for a future "plan to acceptance
 * set" pass on the query side.</p>
 *
 * <p>Mirrors the C ABI {@code WwGoal} (16 bytes).</p>
 */
public record WwGoal(int x, int y, int plane, int radius) {

    public static WwGoal exact(int x, int y, int plane) {
        return new WwGoal(x, y, plane, 0);
    }
}
