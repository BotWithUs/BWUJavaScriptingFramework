package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.query.EntityType;

import java.util.List;
import java.util.function.Function;

/**
 * Query facade for NPC entities. Provides convenience methods and a
 * fluent query builder that returns rich {@link Npc} wrappers.
 *
 * <h3>Quick usage:</h3>
 * <pre>{@code
 * Npcs npcs = new Npcs(api);
 *
 * // Nearest NPC by name
 * Npc goblin = npcs.nearest("Goblin");
 *
 * // All NPCs with a specific ID
 * List<Npc> guards = npcs.all(3408);
 *
 * // Fluent query
 * Npc target = npcs.query()
 *     .named("Guard")
 *     .visible()
 *     .notInCombat()
 *     .withinDistance(15)
 *     .nearest();
 *
 * // Post-filter on definition fields
 * Npc attackable = npcs.query()
 *     .named("Goblin")
 *     .filter(n -> n.hasOption("Attack"))
 *     .nearest();
 * }</pre>
 *
 * @see Npc
 * @see EntityQuery
 */
public class Npcs {

    private final GameAPI api;

    public Npcs(GameAPI api) {
        this.api = api;
    }

    /**
     * Start a fluent NPC query.
     */
    public Query query() {
        return new Query(api);
    }

    /**
     * Returns the nearest NPC with the given name, or null.
     */
    public Npc nearest(String name) {
        return query().named(name).nearest();
    }

    /**
     * Returns the nearest NPC with the given type ID, or null.
     */
    public Npc nearest(int typeId) {
        return query().withId(typeId).nearest();
    }

    /**
     * Returns all NPCs matching the given name.
     */
    public List<Npc> all(String name) {
        return query().named(name).all();
    }

    /**
     * Returns all NPCs matching the given type ID.
     */
    public List<Npc> all(int typeId) {
        return query().withId(typeId).all();
    }

    /**
     * Fluent query builder for NPCs.
     */
    public static class Query extends EntityQuery<Npc, Query> {

        Query(GameAPI api) {
            super(api, EntityType.NPC);
        }

        @Override
        protected Function<Entity, Npc> wrapFunction() {
            return e -> new Npc(api, e);
        }
    }
}
