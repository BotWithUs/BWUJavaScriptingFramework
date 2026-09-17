package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * NPC query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.npcs()}.
 *
 * <p>Holds a definition cache so repeat
 * {@link com.botwithus.bot.api.GameAPI#getNpcType getNpcType} lookups for
 * the same {@code typeId} are free after the first hit. NpcType is
 * effectively immutable per session, so cache entries never expire — the
 * map only grows, bounded in practice by the number of distinct NPC types
 * a script encounters (typically &lt; 100).</p>
 *
 * <pre>{@code
 * Npc target = api.npcs().query()
 *     .named("Goblin")
 *     .withinDistance(15)
 *     .filter(n -> n.hasOption("Attack"))
 *     .nearest();
 * }</pre>
 */
public final class Npcs {

    private static final Logger log = LoggerFactory.getLogger(Npcs.class);

    private final GameAPI api;
    private final ConcurrentHashMap<Integer, NpcType> defCache = new ConcurrentHashMap<>();
    /** Sentinel for "lookup returned null" — ConcurrentHashMap rejects null values. */
    private static final NpcType NULL_DEF = new NpcType(
            -1, "", 0, false, false, List.of(), -1, -1, List.of(), Map.of());

    public Npcs(GameAPI api) {
        this.api = api;
    }

    /** Start a fluent query. Each call returns a fresh builder. */
    public Query query() {
        return new Query(api, this::lookupType);
    }

    /** Nearest NPC matching {@code name} (case-insensitive contains), or {@code null}. */
    public Npc nearest(String name) {
        return query().named(name).nearest();
    }

    /** Nearest NPC of the given type id, or {@code null}. */
    public Npc nearest(int typeId) {
        return query().withId(typeId).nearest();
    }

    /** All NPCs whose name matches {@code name} (case-insensitive contains). */
    public List<Npc> all(String name) {
        return query().named(name).all();
    }

    /** All NPCs of the given type id. */
    public List<Npc> all(int typeId) {
        return query().withId(typeId).all();
    }

    /**
     * Nearest NPC with the given gameval name (e.g. {@code "HANS"}), or
     * {@code null}. Distinct from {@link #nearest(String)}, which matches the
     * localised <em>display</em> name.
     */
    public Npc nearestByGameval(String gameval) {
        return query().withGameval(gameval).nearest();
    }

    /**
     * All NPCs with the given gameval name. Distinct from {@link #all(String)},
     * which matches the localised <em>display</em> name.
     */
    public List<Npc> allByGameval(String gameval) {
        return query().withGameval(gameval).all();
    }

    /** Drop the definition cache. Useful after a game update or for tests. */
    public void clearDefinitionCache() {
        defCache.clear();
    }

    /** Diagnostic — current size of the NpcType cache. */
    public int definitionCacheSize() {
        return defCache.size();
    }

    /**
     * Pull NpcType from cache or RPC. Returns {@code null} on RPC failure or
     * when the type id doesn't exist; callers must handle null.
     */
    private NpcType lookupType(int typeId) {
        NpcType cached = defCache.get(typeId);
        if (cached != null) {
            return cached == NULL_DEF ? null : cached;
        }
        NpcType fetched;
        try {
            fetched = api.getNpcType(typeId);
        } catch (RuntimeException e) {
            log.debug("getNpcType({}) failed; caching null sentinel", typeId, e);
            fetched = null;
        }
        defCache.put(typeId, fetched != null ? fetched : NULL_DEF);
        return fetched;
    }

    /** Query subclass — wires the snapshot stream + name resolution to {@link EntityQuery}. */
    public static final class Query extends EntityQuery<Npc, Query> {
        private final IntFunction<NpcType> typeLookup;

        Query(GameAPI api, IntFunction<NpcType> typeLookup) {
            super(api);
            this.typeLookup = typeLookup;
        }

        /**
         * Filter by gameval symbolic name, e.g. {@code "HANS"}. Pass several to
         * match any of them. Names are resolved once when the filter is added;
         * an unknown name narrows the query to nothing and logs a warning.
         */
        public Query withGameval(String... gamevals) {
            return withGamevalOf(GamevalType.NPC, gamevals);
        }

        @Override
        protected Stream<Npc> source() {
            GameSnapshot snap = api.snapshot();
            if (snap == null) {
                return Stream.empty();
            }
            return snap.npcs().stream()
                    .map(raw -> new Npc(api, raw, typeLookup));
        }

        @Override
        protected int rawTypeId(Npc t) { return t.typeId(); }

        @Override
        protected String nameOf(Npc t) { return t.name(); }
    }
}
