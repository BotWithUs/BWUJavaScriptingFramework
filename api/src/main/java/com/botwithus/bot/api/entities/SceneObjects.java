package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.SceneObjectInfo;
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
 * <p>Unlike {@link Npcs} (snapshot-backed), scene-object queries go over
 * RPC each time the source is materialised. The producer-side
 * {@code query_locations} handler is currently a stub returning an empty
 * list; once the SceneManager iteration lands, this facade picks up real
 * results without code changes.</p>
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
        /** Pull cap passed to the RPC. Bounded so a misbehaving filter can't
         *  drag back the entire scene; client-side filters/sorts apply on top. */
        private static final int RPC_PULL_CAP = 256;

        Query(GameAPI api, IntFunction<LocationType> typeLookup) {
            super(api);
            this.typeLookup = typeLookup;
        }

        @Override
        protected Stream<SceneObject> source() {
            // Pass the local player tile as the search center if available;
            // otherwise the producer reads with origin (0,0) which won't
            // match anything in practice. The deterministic fallback keeps
            // tests well-defined.
            var lp = api.getLocalPlayer();
            int cx = lp == null ? 0 : lp.tileX();
            int cy = lp == null ? 0 : lp.tileY();
            int cp = lp == null ? -1 : lp.plane();
            List<SceneObjectInfo> raw = api.queryLocations(cx, cy, /*radius*/ 64, cp, RPC_PULL_CAP);
            return raw.stream().map(info -> new SceneObject(api, info, typeLookup));
        }

        @Override protected int rawTypeId(SceneObject t) { return t.typeId(); }
        @Override protected String nameOf(SceneObject t) { return t.name(); }
    }
}
