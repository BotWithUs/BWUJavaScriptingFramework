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
 * Display names ride on the snapshot (wire v24), so {@link EntityQuery#named(String)},
 * {@link EntityQuery#namedExact(String)} and {@link EntityQuery#nameMatching(String)}
 * work on players. Two things to know:</p>
 * <ul>
 *   <li>A player whose name is not known yet ({@link Player#name()} is empty) matches
 *       <em>no</em> name filter, not even {@code named("")}. Players who have only just come
 *       into view, and some players in the world, read empty.</li>
 *   <li>Names are compared with U+00A0 (no-break space) folded to a plain space on both sides.
 *       The client turns the space in some account names into U+00A0, so
 *       {@code namedExact("Sir Test")} finds a player the client calls
 *       {@code "Sir\u00A0Test"}. {@link Player#name()} itself returns the raw string.</li>
 * </ul>
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
        /** U+00A0, which the client writes for the space in some names (cp1252 0xA0). */
        private static final char NO_BREAK_SPACE = '\u00A0';

        Query(GameAPI api) {
            super(api);
        }

        @Override
        protected Stream<Player> source() {
            GameSnapshot snap = api.snapshot();
            if (snap == null) {
                return Stream.empty();
            }
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
            // An unknown name is null, never "": EntityQuery's filters skip null, and an empty
            // string would make named("") match every player whose name is not known yet.
            return t.hasName() ? t.name() : null;
        }

        @Override
        protected String normaliseName(String name) {
            return name.replace(NO_BREAK_SPACE, ' ');
        }
    }
}
