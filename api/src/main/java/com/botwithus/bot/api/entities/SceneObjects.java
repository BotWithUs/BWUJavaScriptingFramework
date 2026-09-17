package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.SceneObjectInfo;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Scene-object query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.objects()}.
 *
 * <p>Snapshot-backed (v15+) — reads from {@code api.snapshot().locations()}
 * each time the source is materialised, mirroring the {@link Npcs} and
 * {@link Players} pattern. The producer publishes scene Locations every
 * tick; the filter chain below picks the resolvable rows (direct LOCATIONs
 * with a real interactId) and wraps them in rich {@link SceneObject}s with
 * lazy {@link LocationType} resolution.</p>
 *
 * <p>The {@link LocationType} cache is held here so repeat
 * {@link SceneObject#getType()} hits don't re-RPC.</p>
 *
 * <pre>{@code
 * SceneObject rift = api.objects().query()
 *     .named("Rift")
 *     .withinDistance(30)
 *     .filter(o -> o.hasOption("Convert"))
 *     .nearest();
 * if (rift != null) rift.interact("Convert");
 * }</pre>
 */
public final class SceneObjects {

    private static final Logger log = LoggerFactory.getLogger(SceneObjects.class);

    private final GameAPI api;
    private final ConcurrentHashMap<Integer, LocationType> defCache = new ConcurrentHashMap<>();
    private static final LocationType NULL_DEF = new LocationType(
            -1, "", 0, 0, 0, 0, false, List.of(), -1, -1, List.of(), -1, Map.of());

    public SceneObjects(GameAPI api) {
        this.api = api;
    }

    public Query query() {
        return new Query(api, this::lookupType);
    }

    public SceneObject nearest(String name) {
        return query().named(name).nearest();
    }

    public SceneObject nearest(int typeId) {
        return query().withId(typeId).nearest();
    }

    public List<SceneObject> all(String name) {
        return query().named(name).all();
    }

    public List<SceneObject> all(int typeId) {
        return query().withId(typeId).all();
    }

    /**
     * Nearest scene object with the given gameval name (e.g.
     * {@code "MCANNONCAVE"}), or {@code null}. Distinct from
     * {@link #nearest(String)}, which matches the localised <em>display</em>
     * name — and unlike a display name, a gameval distinguishes the many
     * scenery types that share one label.
     */
    public SceneObject nearestByGameval(String gameval) {
        return query().withGameval(gameval).nearest();
    }

    /**
     * All scene objects with the given gameval name. Distinct from
     * {@link #all(String)}, which matches the localised <em>display</em> name.
     */
    public List<SceneObject> allByGameval(String gameval) {
        return query().withGameval(gameval).all();
    }

    public void clearDefinitionCache() { defCache.clear(); }
    public int definitionCacheSize()   { return defCache.size(); }

    private LocationType lookupType(int typeId) {
        LocationType cached = defCache.get(typeId);
        if (cached != null) {
            return cached == NULL_DEF ? null : cached;
        }
        LocationType fetched;
        try {
            fetched = api.getLocationType(typeId);
        } catch (RuntimeException e) {
            log.debug("getLocationType({}) failed; caching null sentinel", typeId, e);
            fetched = null;
        }
        defCache.put(typeId, fetched != null ? fetched : NULL_DEF);
        return fetched;
    }

    public static final class Query extends EntityQuery<SceneObject, Query> {
        private final IntFunction<LocationType> typeLookup;

        Query(GameAPI api, IntFunction<LocationType> typeLookup) {
            super(api);
            this.typeLookup = typeLookup;
        }

        /**
         * Filter by gameval symbolic name, e.g. {@code "MCANNONCAVE"} — the
         * cache's {@code loc} namespace. Pass several to match any of them.
         * Names are resolved once when the filter is added; an unknown name
         * narrows the query to nothing and logs a warning.
         */
        public Query withGameval(String... gamevals) {
            return withGamevalOf(GamevalType.LOC, gamevals);
        }

        @Override
        protected Stream<SceneObject> source() {
            // Both LOCATION variants are interactable scene entries:
            //   - Direct LOCATION: the loc id (cache key + action param1)
            //     lives in interactId; typeId on this variant is a small
            //     entity-kind classifier and not useful.
            //   - Combined section: each section is one tile of a
            //     multi-tile loc (most trees, rocks, posts). interactId is
            //     -1; the loc id lives in typeId (resolved from the
            //     section's own ConfigType shared_ptr on the producer).
            // Either way we surface the row and use whichever field
            // carries the real loc id. Hidden / deleted rows are dropped.
            GameSnapshot snap = api.snapshot();
            if (snap == null) {
                return Stream.empty();
            }
            return snap.locations().stream()
                    .filter(LocationFilter.visible())
                    .map(l -> {
                        int locId = l.isCombinedSection() ? l.typeId() : l.interactId();
                        if (locId <= 0) return null;
                        return new SceneObject(
                                api,
                                new SceneObjectInfo(
                                        locId,                // handle (action param1)
                                        locId,                // typeId → cache lookup
                                        l.tileX(), l.tileY(), l.plane(),
                                        "",
                                        List.of()),
                                typeLookup);
                    })
                    .filter(java.util.Objects::nonNull);
        }

        @Override protected int rawTypeId(SceneObject t) { return t.typeId(); }
        @Override protected String nameOf(SceneObject t) { return t.name(); }
    }
}
