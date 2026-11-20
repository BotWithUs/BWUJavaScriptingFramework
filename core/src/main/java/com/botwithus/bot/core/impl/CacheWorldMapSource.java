package com.botwithus.bot.core.impl;

import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.cache.WmePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link WorldMapElementIndex.Source} over the host's {@link NXTCache}. With no cache, or an
 * NXTCache.dll older than the placement export, every area reads empty and one warning says
 * why.
 */
final class CacheWorldMapSource implements WorldMapElementIndex.Source {

    private static final Logger log = LoggerFactory.getLogger(CacheWorldMapSource.class);

    private final NXTCache cache;
    private final AtomicBoolean hasWarned = new AtomicBoolean();

    /** @param cache the host's cache, or {@code null} when it has none */
    CacheWorldMapSource(NXTCache cache) {
        this.cache = cache;
    }

    @Override
    public List<WmePlacement> area(int areaId) {
        return isAvailable() ? cache.worldMapAreaElements(areaId) : List.of();
    }

    @Override
    public List<WmePlacement> all() {
        return isAvailable() ? cache.worldMapElements() : List.of();
    }

    @Override
    public Optional<String> definitionJson(int elementId) {
        return cache == null ? Optional.empty() : Optional.ofNullable(cache.getWorldmapJson(elementId));
    }

    private boolean isAvailable() {
        boolean isAvailable = cache != null && NXTCache.supportsWorldMapElements();
        if (!isAvailable && hasWarned.compareAndSet(false, true)) {
            log.warn("World map elements unavailable: {}", cache == null
                    ? "this host has no NXTCache"
                    : "NXTCache.dll predates nxt_get_worldmap_elements; update it");
        }
        return isAvailable;
    }
}
