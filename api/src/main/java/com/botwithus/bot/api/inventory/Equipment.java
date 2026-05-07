package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ItemType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The local player's worn equipment (inv id 94, equipment iface 1464).
 * Singleton per {@link GameAPI}; obtain via {@code api.equipment()}.
 *
 * <p>Equipment slots have fixed semantic meaning (head, body, legs, ...)
 * unlike a free-form inventory grid; the {@link InventoryContainer} read
 * methods still work but {@link #interact(int, int) slot click} interacts
 * with whichever slot is wired to component 15 in the equipment iface.
 * Subclass-specific helpers for "remove headgear" and similar will land
 * once the per-slot component map is surveyed.</p>
 */
public final class Equipment extends InventoryContainer {

    public static final int INVENTORY_ID = 94;
    public static final int INTERFACE_ID = 1464;
    public static final int COMPONENT_ID = 15;

    private final ConcurrentHashMap<Integer, ItemType> defCache;

    public Equipment(GameAPI api) {
        this(api, new ConcurrentHashMap<>());
    }

    private Equipment(GameAPI api, ConcurrentHashMap<Integer, ItemType> cache) {
        super(api, INVENTORY_ID, INTERFACE_ID, COMPONENT_ID,
                cachedItemTypeLookup(api, cache));
        this.defCache = cache;
    }

    public void clearDefinitionCache() { defCache.clear(); }
    public int definitionCacheSize()   { return defCache.size(); }
}
