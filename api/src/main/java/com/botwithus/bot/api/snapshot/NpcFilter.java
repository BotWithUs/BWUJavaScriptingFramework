package com.botwithus.bot.api.snapshot;

import java.util.function.Predicate;

/**
 * Predicate for filtering {@link Npc} entries in a {@link GameSnapshot}.
 * Compose with {@link Predicate#and(Predicate)} / {@link Predicate#or(Predicate)}.
 */
@FunctionalInterface
public interface NpcFilter extends Predicate<Npc> {

    @Override
    default NpcFilter and(Predicate<? super Npc> other) {
        return n -> test(n) && other.test(n);
    }

    @Override
    default NpcFilter or(Predicate<? super Npc> other) {
        return n -> test(n) || other.test(n);
    }

    @Override
    default NpcFilter negate() {
        return n -> !test(n);
    }

    static NpcFilter typeId(int typeId) {
        return n -> n.typeId() == typeId;
    }

    static NpcFilter serverIndex(int serverIndex) {
        return n -> n.serverIndex() == serverIndex;
    }

    static NpcFilter onPlane(int plane) {
        return n -> n.plane() == plane;
    }

    static NpcFilter moving() {
        return Npc::isMoving;
    }

    static NpcFilter notMoving() {
        return n -> !n.isMoving();
    }

    static NpcFilter alive() {
        return n -> n.hp() > 0;
    }
}
