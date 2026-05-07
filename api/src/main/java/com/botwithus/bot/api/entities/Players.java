package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;
import java.util.stream.Stream;

/**
 * Player query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.players()}.
 *
 * <p>Players don't carry a per-id type definition (no {@code PlayerType}
 * cache like {@code NpcType}), so this facade is mostly a query entry point.
 * Display names ride along on the snapshot in future slices once the
 * producer surfaces them; for now {@link EntityQuery#named(String)} on
 * players returns nothing — use index/position filters or the predicate
 * form for arbitrary checks.</p>
 */
public final class Players {

    private final GameAPI api;

    public Players(GameAPI api) {
        this.api = api;
    }

    /** Start a fluent query. */
    public Query query() {
        return new Query(api);
    }

    public Player byServerIndex(int serverIndex) {
        return query().filter(p -> p.serverIndex() == serverIndex).first();
    }

    public List<Player> all() {
        return query().all();
    }

    public static final class Query extends EntityQuery<Player, Query> {
        Query(GameAPI api) {
            super(api);
        }

        @Override
        protected Stream<Player> source() {
            GameSnapshot snap = api.snapshot();
            if (snap == null) return Stream.empty();
            return snap.players().stream().map(raw -> new Player(api, raw));
        }

        @Override
        protected int rawTypeId(Player t) {
            // Players have no type id. Returning -1 makes withId(N) never match,
            // which is the right behavior for callers who try.
            return -1;
        }

        @Override
        protected String nameOf(Player t) {
            // Display names aren't on the wire yet; named()/nameMatching() will
            // never match. Returning null is checked by EntityQuery's filters.
            return null;
        }
    }
}
