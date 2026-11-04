package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.HintArrow;
import com.botwithus.bot.api.model.HintArrowType;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Hint-arrow queries; obtain one from {@code api.hintArrows()}.
 *
 * <pre>{@code
 * HintArrow pointer = api.hintArrows()
 *     .ofType(HintArrowType.TILE_CORNER)
 *     .nearPlayer()
 *     .nearest();
 * boolean markedForMe = api.hintArrows().targetingPlayer(self.serverIndex()).exists();
 * }</pre>
 *
 * <p>The RPC takes no filters, so every terminal fetches the whole list and filters in Java. A
 * refused read looks like no arrows here, and a truncated list looks complete;
 * {@link GameAPI#queryHintArrows()} is how to see either.</p>
 *
 * <p><b>The position filters only match tile arrows</b> ({@link HintArrow#hasTile()}). An NPC or
 * player arrow carries an index, not a tile: find it with {@link Query#targetingNpc} or
 * {@link Query#targetingPlayer}.</p>
 */
public final class HintArrows {

    /** Default tile radius for {@link Query#nearPlayer()}; covers a typical map view. */
    private static final int DEFAULT_QUERY_RADIUS_TILES = 64;

    private HintArrows() {
    }

    /** A new query against {@code api}. */
    public static Query query(GameAPI api) {
        return new Query(api);
    }

    /** Fluent filter over {@link GameAPI#queryHintArrows()}. */
    public static final class Query {

        private final GameAPI api;
        private Predicate<HintArrow> postFilter = a -> true;
        private boolean hasCenter;
        private int centerX;
        private int centerY;

        private Query(GameAPI api) {
            this.api = api;
        }

        /** Arrows of any of these types. Passing none matches nothing. */
        public Query ofType(HintArrowType... types) {
            Set<HintArrowType> wanted = Set.copyOf(Arrays.asList(types));
            return filter(a -> wanted.contains(a.type()));
        }

        /** Arrows pointing at the NPC with this server index. */
        public Query targetingNpc(int serverIndex) {
            return filter(a -> a.type() == HintArrowType.NPC && a.targetIndex() == serverIndex);
        }

        /** Arrows pointing at the player with this server index. */
        public Query targetingPlayer(int serverIndex) {
            return filter(a -> a.type() == HintArrowType.PLAYER && a.targetIndex() == serverIndex);
        }

        /** Tile arrows on exactly this tile, on any plane; combine with {@link #onPlane}. */
        public Query onTile(int tileX, int tileY) {
            return filter(a -> a.hasTile() && a.tileX() == tileX && a.tileY() == tileY);
        }

        /** Tile arrows within {@code radius} tiles (Chebyshev), and the centre for {@link #nearest()}. */
        public Query near(int tileX, int tileY, int radius) {
            hasCenter = true;
            centerX = tileX;
            centerY = tileY;
            return filter(a -> a.hasTile() && a.distanceTo(tileX, tileY) <= radius);
        }

        /**
         * Tile arrows within {@code radius} tiles of the local player, on its plane. With no local
         * player this matches nothing rather than widening to every arrow.
         */
        public Query nearPlayer(int radius) {
            LocalPlayer self = api.getLocalPlayer();
            if (self == null) {
                return filter(a -> false);
            }
            return near(self.tileX(), self.tileY(), radius).onPlane(self.plane());
        }

        /** {@link #nearPlayer(int)} with a radius that covers a typical map view. */
        public Query nearPlayer() {
            return nearPlayer(DEFAULT_QUERY_RADIUS_TILES);
        }

        /** Arrows on this plane. */
        public Query onPlane(int plane) {
            return filter(a -> a.plane() == plane);
        }

        /** Arrows also matching {@code predicate}. */
        public Query filter(Predicate<HintArrow> predicate) {
            postFilter = postFilter.and(predicate);
            return this;
        }

        /** Every matching arrow, in the order the client holds them. */
        public List<HintArrow> all() {
            return api.queryHintArrows().value().stream().filter(postFilter).toList();
        }

        /** The first matching arrow, or {@code null}. */
        public HintArrow first() {
            List<HintArrow> arrows = all();
            return arrows.isEmpty() ? null : arrows.getFirst();
        }

        /** {@link #first()} as an {@link Optional}. */
        public Optional<HintArrow> findFirst() {
            return Optional.ofNullable(first());
        }

        /**
         * The matching tile arrow closest to the {@link #near} centre, else to the local player, or
         * {@code null}. Arrows with no tile are never nearest, and with no centre and no local
         * player there is nothing to measure from, so this is {@code null}.
         */
        public HintArrow nearest() {
            LocalPlayer self = hasCenter ? null : api.getLocalPlayer();
            if (!hasCenter && self == null) {
                return null;
            }
            int x = hasCenter ? centerX : self.tileX();
            int y = hasCenter ? centerY : self.tileY();
            return all().stream()
                    .filter(HintArrow::hasTile)
                    .min(Comparator.comparingInt(a -> a.distanceTo(x, y)))
                    .orElse(null);
        }

        /** {@link #nearest()} as an {@link Optional}. */
        public Optional<HintArrow> findNearest() {
            return Optional.ofNullable(nearest());
        }

        /** True when any arrow matches. */
        public boolean exists() {
            return !all().isEmpty();
        }

        /** How many arrows match. */
        public int count() {
            return all().size();
        }
    }
}
