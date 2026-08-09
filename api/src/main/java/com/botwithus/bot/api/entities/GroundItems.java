package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
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
 * <p>Snapshot-backed (v15+) — reads from {@code api.snapshot().groundItems()}
 * each time the source is materialised, mirroring the {@link Npcs} and
 * {@link SceneObjects} pattern. The producer publishes every alive ground
 * stack within the loaded-scene tile bounds each tick.</p>
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

    /**
     * Nearest ground stack of the item with the given gameval name (e.g.
     * {@code "COINS"}), or {@code null}.
     */
    public GroundItem nearestByGameval(String gameval) {
        return query().withGameval(gameval).nearest();
    }

    /** All ground stacks of the item with the given gameval name. */
    public List<GroundItem> allByGameval(String gameval) {
        return query().withGameval(gameval).all();
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

        Query(GameAPI api, IntFunction<ItemType> typeLookup) {
            super(api);
            this.typeLookup = typeLookup;
        }

        /**
         * Filter by the item's gameval symbolic name, e.g. {@code "COINS"}.
         * Pass several to match any of them. Names are resolved once when the
         * filter is added; an unknown name narrows the query to nothing and
         * logs a warning.
         */
        public Query withGameval(String... gamevals) {
            return withGamevalOf(GamevalType.ITEM, gamevals);
        }

        @Override
        protected Stream<GroundItem> source() {
            // Producer already drops empty/dead stacks; consumer-side filters
            // (named, withId, withinDistance, ...) compose downstream.
            // handle == itemId mirrors the retired RPC convention so the rich
            // GroundItem.interact path keeps the action queue's param1 stable.
            GameSnapshot snap = api.snapshot();
            if (snap == null) {
                return Stream.empty();
            }
            return snap.groundItems().stream()
                    .map(g -> new GroundItem(
                            api,
                            new GroundItemInfo(
                                    g.itemId(),  // handle
                                    g.itemId(),
                                    g.quantity(),
                                    g.tileX(),
                                    g.tileY(),
                                    g.plane()),
                            typeLookup));
        }

        /**
         * Item id treated as the "type id" for {@link EntityQuery#withId withId}
         * filters. So {@code .withId(995)} matches coin stacks.
         */
        @Override protected int rawTypeId(GroundItem t) { return t.itemId(); }
        @Override protected String nameOf(GroundItem t) { return t.name(); }
    }
}
