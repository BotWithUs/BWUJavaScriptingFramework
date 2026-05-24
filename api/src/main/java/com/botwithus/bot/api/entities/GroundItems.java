package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Ground-item query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.groundItems()}.
 *
 * <p>RPC-backed (same shape as {@link SceneObjects}). Stub-empty until the
 * producer-side ObjStackList iteration lands.</p>
 *
 * <pre>{@code
 * GroundItem coins = api.groundItems().query()
 *     .filter(g -> g.itemId() == 995)
 *     .nearest();
 * if (coins != null) coins.interact("Take");
 * }</pre>
 */
public final class GroundItems {

    private static final Logger log = LoggerFactory.getLogger(GroundItems.class);

    private final GameAPI api;
    private final ConcurrentHashMap<Integer, ItemType> defCache = new ConcurrentHashMap<>();
    private static final ItemType NULL_DEF = new ItemType(
            -1, "", false, false, 0, 0, 0, -1, -1, false, List.of(), List.of(), Map.of());

    public GroundItems(GameAPI api) {
        this.api = api;
    }

    public Query query() {
        return new Query(api, this::lookupType);
    }

    public void clearDefinitionCache() { defCache.clear(); }
    public int definitionCacheSize()   { return defCache.size(); }

    private ItemType lookupType(int itemId) {
        ItemType cached = defCache.get(itemId);
        if (cached != null) {
            return cached == NULL_DEF ? null : cached;
        }
        ItemType fetched;
        try {
            fetched = api.getItemType(itemId);
        } catch (RuntimeException e) {
            log.debug("getItemType({}) failed; caching null sentinel", itemId, e);
            fetched = null;
        }
        defCache.put(itemId, fetched != null ? fetched : NULL_DEF);
        return fetched;
    }

    public static final class Query extends EntityQuery<GroundItem, Query> {
        private final IntFunction<ItemType> typeLookup;
        private static final int RPC_PULL_CAP = 256;

        Query(GameAPI api, IntFunction<ItemType> typeLookup) {
            super(api);
            this.typeLookup = typeLookup;
        }

        @Override
        protected Stream<GroundItem> source() {
            var lp = api.getLocalPlayer();
            int cx = lp == null ? 0 : lp.tileX();
            int cy = lp == null ? 0 : lp.tileY();
            int cp = lp == null ? -1 : lp.plane();
            List<GroundItemInfo> raw = api.queryGroundItems(cx, cy, /*radius*/ 64, cp, RPC_PULL_CAP);
            return raw.stream().map(info -> new GroundItem(api, info, typeLookup));
        }

        /**
         * Item id treated as the "type id" for {@link EntityQuery#withId withId}
         * filters. So {@code .withId(995)} matches coin stacks.
         */
        @Override protected int rawTypeId(GroundItem t) { return t.itemId(); }
        @Override protected String nameOf(GroundItem t) { return t.name(); }
    }
}
