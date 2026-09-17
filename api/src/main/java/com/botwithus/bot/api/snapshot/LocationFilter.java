package com.botwithus.bot.api.snapshot;

import java.util.function.Predicate;

/**
 * Predicate for filtering {@link Location} entries in a {@link GameSnapshot}.
 * Compose with {@link Predicate#and(Predicate)} / {@link Predicate#or(Predicate)}.
 */
@FunctionalInterface
public interface LocationFilter extends Predicate<Location> {

    @Override
    default LocationFilter and(Predicate<? super Location> other) {
        return l -> test(l) && other.test(l);
    }

    @Override
    default LocationFilter or(Predicate<? super Location> other) {
        return l -> test(l) || other.test(l);
    }

    @Override
    default LocationFilter negate() {
        return l -> !test(l);
    }

    static LocationFilter typeId(int typeId) {
        return l -> l.typeId() == typeId;
    }

    static LocationFilter interactId(int interactId) {
        return l -> l.interactId() == interactId;
    }

    static LocationFilter onPlane(int plane) {
        return l -> l.plane() == plane;
    }

    static LocationFilter atTile(int tileX, int tileY) {
        return l -> l.tileX() == tileX && l.tileY() == tileY;
    }

    static LocationFilter atTile(int tileX, int tileY, int plane) {
        return l -> l.tileX() == tileX && l.tileY() == tileY && l.plane() == plane;
    }

    static LocationFilter combinedSection() {
        return Location::isCombinedSection;
    }

    static LocationFilter direct() {
        return l -> !l.isCombinedSection();
    }

    static LocationFilter visible() {
        return l -> !l.isHidden() && !l.isDeleted();
    }

    static LocationFilter animating() {
        return l -> l.animationId() != -1;
    }
}
