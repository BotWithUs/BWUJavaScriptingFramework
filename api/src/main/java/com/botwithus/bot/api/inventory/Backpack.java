package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ItemType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The local player's backpack (inv id 93, slot grid at iface 1473 comp 5).
 * Singleton per {@link GameAPI}; obtain via {@code api.backpack()}.
 *
 * <p>Inherits all read/containment/interaction methods from
 * {@link InventoryContainer}. The slot click route is iface 1473 comp 5,
 * which is the {@link com.botwithus.bot.api.util.Interfaces#BACKPACK}
 * inventory grid.</p>
 */
public final class Backpack extends InventoryContainer {

    public static final int INVENTORY_ID = 93;
    public static final int INTERFACE_ID = 1473;
    public static final int COMPONENT_ID = 5;

    /** Shared so {@link #clearDefinitionCache()} and {@link #definitionCacheSize()}
     *  reflect the same map the lookup closure mutates. */
    private final ConcurrentHashMap<Integer, ItemType> defCache;

    public Backpack(GameAPI api) {
        this(api, new ConcurrentHashMap<>());
    }

    /** Constructor used to share the cache reference between super-ctor lookup
     *  closure and instance fields — necessary because Java forbids referencing
     *  {@code this} fields before the super call. */
    private Backpack(GameAPI api, ConcurrentHashMap<Integer, ItemType> cache) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID,
                cachedItemTypeLookup(api, cache));
        this.defCache = cache;
    }

    /** Drop the ItemType cache. Useful between game updates or in tests. */
    public void clearDefinitionCache() {
        defCache.clear();
    }

    /** Diagnostic — number of cached ItemType definitions. */
    public int definitionCacheSize() {
        return defCache.size();
    }

    /** Convenience: equivalent to {@link #interactFirst(int, String)} for clarity. */
    public boolean use(int itemId) {
        return interactFirst(itemId, "Use");
    }
}
