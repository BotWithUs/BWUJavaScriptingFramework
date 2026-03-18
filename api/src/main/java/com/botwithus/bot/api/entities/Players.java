package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Entity;

import java.util.List;
import java.util.function.Function;

/**
 * Query facade for player entities. Provides convenience methods and a
 * fluent query builder that returns rich {@link Player} wrappers.
 *
 * <h3>Quick usage:</h3>
 * <pre>{@code
 * Players players = new Players(api);
 *
 * // Nearest player
 * Player nearest = players.nearest();
 *
 * // Find a specific player
 * Player friend = players.nearest("PlayerName");
 *
 * // Fluent query
 * List<Player> nearby = players.query()
 *     .visible()
 *     .withinDistance(10)
 *     .all();
 * }</pre>
 *
 * @see Player
 * @see EntityQuery
 */
public class Players {

    private final GameAPI api;

    public Players(GameAPI api) {
        this.api = api;
    }

    /**
     * Start a fluent player query.
     */
    public Query query() {
        return new Query(api);
    }

    /**
     * Returns the nearest player (excluding the local player), or null.
     */
    public Player nearest() {
        var lp = api.getLocalPlayer();
        return query()
                .filter(p -> p.serverIndex() != lp.serverIndex())
                .nearest();
    }

    /**
     * Returns the nearest player with the given name, or null.
     */
    public Player nearest(String name) {
        return query().named(name).nearest();
    }

    /**
     * Returns all visible players (excluding the local player).
     */
    public List<Player> all() {
        var lp = api.getLocalPlayer();
        return query()
                .filter(p -> p.serverIndex() != lp.serverIndex())
                .all();
    }

    /**
     * Returns all players matching the given name.
     */
    public List<Player> all(String name) {
        return query().named(name).all();
    }

    /**
     * Fluent query builder for players.
     */
    public static class Query extends EntityQuery<Player, Query> {

        Query(GameAPI api) {
            super(api, "player");
        }

        @Override
        protected Function<Entity, Player> wrapFunction() {
            return e -> new Player(api, e);
        }
    }
}
