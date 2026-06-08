package com.botwithus.bot.api.snapshot;

import java.util.function.Predicate;

/**
 * Predicate for filtering {@link GroundItem} entries in a {@link GameSnapshot}.
 * Compose with {@link Predicate#and(Predicate)} / {@link Predicate#or(Predicate)}.
 */
@FunctionalInterface
public interface GroundItemFilter extends Predicate<GroundItem> {

    @Override
    default GroundItemFilter and(Predicate<? super GroundItem> other) {
        return g -> test(g) && other.test(g);
    }

    @Override
    default GroundItemFilter or(Predicate<? super GroundItem> other) {
        return g -> test(g) || other.test(g);
    }

    @Override
    default GroundItemFilter negate() {
        return g -> !test(g);
    }

    static GroundItemFilter itemId(int itemId) {
        return g -> g.itemId() == itemId;
    }

    static GroundItemFilter onPlane(int plane) {
        return g -> g.plane() == plane;
    }

    static GroundItemFilter atTile(int tileX, int tileY) {
        return g -> g.tileX() == tileX && g.tileY() == tileY;
    }

    static GroundItemFilter atTile(int tileX, int tileY, int plane) {
        return g -> g.tileX() == tileX && g.tileY() == tileY && g.plane() == plane;
    }

    static GroundItemFilter minQuantity(int minQty) {
        return g -> g.quantity() >= minQty;
    }
}
