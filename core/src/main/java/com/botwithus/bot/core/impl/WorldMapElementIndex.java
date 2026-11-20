package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldMapPlacement;
import com.botwithus.bot.core.cache.WmePlacement;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Answers {@code GameAPI.queryWorldMapElements} from the cache: world map element
 * placements (cache index 41) joined with the definition each names.
 *
 * <p>Both are cache-resident and stable until a game update, so an area's placements and
 * each definition are read once and kept. The filter keys are the ones
 * {@link WorldMapElements.Query} writes; see that class for what each means.</p>
 */
final class WorldMapElementIndex {

    private static final Logger log = LoggerFactory.getLogger(WorldMapElementIndex.class);

    /**
     * Filter keys the cache has no data for. A query naming one matches nothing, rather
     * than ignoring the filter and widening the answer.
     */
    static final Set<String> UNSUPPORTED_KEYS = Set.of("skill_id", "min_level", "max_level",
            "with_description", "with_resources", "item_id", "resource_name");
    private static final int NO_LEVEL = -1;
    private static final int NO_SPRITE = -1;
    private static final int NO_CATEGORY = -1;
    /** Placement in memo of {@link #definitions} for an id the cache cannot decode. */
    private static final Definition MISSING = new Definition("", "", NO_CATEGORY, NO_SPRITE);

    /** Where placements and definitions come from: the cache, or a test's fake. */
    interface Source {
        /** Every placement of {@code areaId}. */
        List<WmePlacement> area(int areaId);

        /** Every placement of every area. */
        List<WmePlacement> all();

        /** The definition JSON of {@code elementId}, empty when the cache has none. */
        Optional<String> definitionJson(int elementId);
    }

    /** The fields of a definition this host uses. */
    record Definition(String name, String tooltip, int category, int spriteId) {
    }

    private final Source source;
    private final Map<Integer, List<WmePlacement>> areas = new ConcurrentHashMap<>();
    private final Map<Integer, Definition> definitions = new ConcurrentHashMap<>();
    private volatile List<WmePlacement> everyArea;

    WorldMapElementIndex(Source source) {
        this.source = source;
    }

    /** The placements {@code filter} selects, each joined with its definition. */
    List<WorldMapElement> query(Map<String, Object> filter) {
        for (String key : UNSUPPORTED_KEYS) {
            if (filter.containsKey(key)) {
                log.debug("world map filter '{}' has no cache data; the query matches nothing", key);
                return List.of();
            }
        }
        Predicate<WmePlacement> where = placementFilter(filter);
        List<WorldMapElement> out = new ArrayList<>();
        for (WmePlacement p : placements(intOr(filter, WorldMapElements.AREA_KEY,
                WorldMapElements.MAIN_AREA_ID))) {
            if (where.test(p)) {
                WorldMapElement e = toElement(p, definition(p.elementId()));
                if (matchesText(e, filter)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private List<WmePlacement> placements(int areaId) {
        if (areaId == WorldMapElements.ALL_AREAS) {
            List<WmePlacement> all = everyArea;
            if (all == null) {
                all = List.copyOf(source.all());
                everyArea = all;
            }
            return all;
        }
        return areas.computeIfAbsent(areaId, id -> List.copyOf(source.area(id)));
    }

    private static Predicate<WmePlacement> placementFilter(Map<String, Object> filter) {
        Predicate<WmePlacement> where = p -> true;
        if (filter.containsKey("plane")) {
            int plane = intOr(filter, "plane", 0);
            where = where.and(p -> p.plane() == plane);
        }
        if (filter.containsKey("radius")) {
            int cx = intOr(filter, "tile_x", 0);
            int cy = intOr(filter, "tile_y", 0);
            int r = intOr(filter, "radius", 0);
            where = where.and(p -> Math.max(Math.abs(p.x() - cx), Math.abs(p.y() - cy)) <= r);
        }
        return where;
    }

    private static boolean matchesText(WorldMapElement e, Map<String, Object> filter) {
        if (filter.containsKey("category") && e.category() != intOr(filter, "category", NO_CATEGORY)) {
            return false;
        }
        Object name = filter.get("name");
        if (name == null) {
            return true;
        }
        String needle = name.toString().toLowerCase(Locale.ROOT);
        return e.name().toLowerCase(Locale.ROOT).contains(needle)
                || e.tooltip().toLowerCase(Locale.ROOT).contains(needle);
    }

    private Definition definition(int elementId) {
        return definitions.computeIfAbsent(elementId,
                id -> source.definitionJson(id).map(WorldMapElementIndex::parseDefinition).orElse(MISSING));
    }

    /**
     * The definition JSON's fields. Its {@code elementId} key is the category (WME opcode
     * 19), not an element id: the decoder's name for it predates knowing what it was.
     */
    static Definition parseDefinition(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new Definition(text(o, "name"), text(o, "tooltip"), number(o, "elementId", NO_CATEGORY),
                number(o, "spriteId", NO_SPRITE));
    }

    private static WorldMapElement toElement(WmePlacement p, Definition d) {
        return new WorldMapElement(p.elementId(), p.x(), p.y(), p.plane(), d.category(), d.spriteId(),
                p.elementId(), d.name(), d.tooltip(), "", NO_LEVEL, NO_LEVEL, NO_LEVEL, NO_LEVEL,
                List.of(), List.of(), List.of(new WorldMapPlacement(p.plane(), p.x(), p.y(), false)),
                p.areaId(), p.flags());
    }

    private static String text(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    private static int number(JsonObject o, String key, int fallback) {
        JsonElement v = o.get(key);
        return v == null || !v.isJsonPrimitive() ? fallback : v.getAsInt();
    }

    private static int intOr(Map<String, Object> filter, String key, int fallback) {
        return MapHelper.getIntOr(filter, key, fallback);
    }
}
