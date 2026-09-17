package com.botwithus.bot.api.snapshot;

import java.util.function.Predicate;

/**
 * Predicate for filtering {@link Player} entries in a {@link GameSnapshot}.
 * Compose with {@link Predicate#and(Predicate)} / {@link Predicate#or(Predicate)}.
 */
@FunctionalInterface
public interface PlayerFilter extends Predicate<Player> {

    @Override
    default PlayerFilter and(Predicate<? super Player> other) {
        return p -> test(p) && other.test(p);
    }

    @Override
    default PlayerFilter or(Predicate<? super Player> other) {
        return p -> test(p) || other.test(p);
    }

    @Override
    default PlayerFilter negate() {
        return p -> !test(p);
    }

    static PlayerFilter serverIndex(int serverIndex) {
        return p -> p.serverIndex() == serverIndex;
    }

    static PlayerFilter onPlane(int plane) {
        return p -> p.plane() == plane;
    }

    static PlayerFilter combatLevelBetween(int minInclusive, int maxInclusive) {
        return p -> p.combatLevel() >= minInclusive && p.combatLevel() <= maxInclusive;
    }

    static PlayerFilter moving() {
        return Player::isMoving;
    }

    static PlayerFilter notMoving() {
        return p -> !p.isMoving();
    }
}
