package com.botwithus.bot.api.world;

import com.botwithus.bot.api.domain.NavigationAPI;
import com.botwithus.bot.api.entities.EntityContext;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * A set of world tiles on one plane. {@link RectangularArea}, {@link CircularArea} and
 * {@link PolygonalArea} are the stock shapes; a script may implement its own.
 *
 * <pre>{@code
 * Area bank = RectangularArea.of(new Coordinate(3180, 3433, 0), new Coordinate(3185, 3446, 0));
 * if (!bank.contains(api.getLocalPlayer())) {
 *     bank.randomReachableTile(api, 10).ifPresent(t -> api.walkWorldPath(t.x(), t.y()));
 * }
 * Npc guard = api.npcs().query().named("Guard").inside(bank).nearest();
 * }</pre>
 */
public interface Area {

    /** Attempts {@link #randomReachableTile(NavigationAPI)} makes before giving up. */
    int DEFAULT_REACHABLE_ATTEMPTS = 10;

    /** The plane every tile of this area is on. */
    int plane();

    /** Whether the tile {@code x}, {@code y} on {@code plane} is in this area. */
    boolean contains(int x, int y, int plane);

    /**
     * A uniformly random tile of this area.
     *
     * @param random the source of randomness, so a caller can seed it
     */
    Coordinate randomTile(RandomGenerator random);

    /** Whether {@code tile} is in this area. */
    default boolean contains(Coordinate tile) {
        return contains(tile.x(), tile.y(), tile.plane());
    }

    /** Whether the tile {@code entity} stands on is in this area; {@code false} for null. */
    default boolean contains(EntityContext entity) {
        return entity != null && contains(entity.tileX(), entity.tileY(), entity.plane());
    }

    /**
     * The smallest rectangle holding every tile of this area, when the shape knows it. The
     * three stock shapes do; an area a script implements itself answers empty unless it
     * overrides this. A pathfinder avoid area is built from it.
     */
    default Optional<RectangularArea> boundingBox() {
        return Optional.empty();
    }

    /** A uniformly random tile of this area. */
    default Coordinate randomTile() {
        return randomTile(ThreadLocalRandom.current());
    }

    /**
     * A random tile of this area that the local player can walk to, checked with
     * {@link NavigationAPI#isReachable(int, int)} — one local pathfind per attempt.
     *
     * <p>Reachability is judged from where the player stands, on the player's plane, so an
     * area on another plane, or out of the loaded scene, yields nothing.</p>
     *
     * @param nav      the reachability check, normally the script's {@code GameAPI}
     * @param attempts how many random tiles to try
     * @return a reachable tile, or empty when none of the attempts was reachable
     */
    default Optional<Coordinate> randomReachableTile(NavigationAPI nav, int attempts) {
        return randomReachableTile(nav, attempts, ThreadLocalRandom.current());
    }

    /** {@link #randomReachableTile(NavigationAPI, int)} with {@link #DEFAULT_REACHABLE_ATTEMPTS}. */
    default Optional<Coordinate> randomReachableTile(NavigationAPI nav) {
        return randomReachableTile(nav, DEFAULT_REACHABLE_ATTEMPTS);
    }

    /** {@link #randomReachableTile(NavigationAPI, int)} on a caller-supplied random source. */
    default Optional<Coordinate> randomReachableTile(NavigationAPI nav, int attempts,
                                                     RandomGenerator random) {
        for (int i = 0; i < attempts; i++) {
            Coordinate tile = randomTile(random);
            if (nav.isReachable(tile.x(), tile.y())) {
                return Optional.of(tile);
            }
        }
        return Optional.empty();
    }
}
