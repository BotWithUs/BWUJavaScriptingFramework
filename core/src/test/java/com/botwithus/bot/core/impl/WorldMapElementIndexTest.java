package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.core.cache.WmePlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache-backed world map query. Placements and definitions are shaped as
 * NXTCacheLibrary dev 03d14f1a returns them; the definition JSON's {@code elementId} key
 * is the category, as that revision's own test reads it.
 */
class WorldMapElementIndexTest {

    private static final int LUMBRIDGE_LODESTONE_DEF = 3612;
    private static final int LUMBRIDGE_LABEL_DEF = 500;
    private static final int PRIF_LODESTONE_DEF = 3700;
    private static final int HOME_TELEPORT_CATEGORY = 2613;
    private static final int PRIF_AREA = 231;

    private final FakeSource source = new FakeSource();
    private final WorldMapElementIndex index = new WorldMapElementIndex(source);

    FakeSource populated() {
        source.areas.put(WorldMapElements.MAIN_AREA_ID, List.of(
                new WmePlacement(28, LUMBRIDGE_LODESTONE_DEF, 3233, 3222, 0, 0),
                new WmePlacement(28, LUMBRIDGE_LABEL_DEF, 3220, 3240, 0, 5),
                new WmePlacement(28, PRIF_LODESTONE_DEF, 4576, 2665, 0, 0)));
        source.areas.put(PRIF_AREA, List.of(new WmePlacement(PRIF_AREA, PRIF_LODESTONE_DEF, 2208, 3361, 0, 0)));
        source.defs.put(LUMBRIDGE_LODESTONE_DEF,
                "{\"id\":3612,\"name\":\"\",\"tooltip\":\"Lumbridge lodestone\",\"elementId\":2613,\"spriteId\":-1}");
        source.defs.put(LUMBRIDGE_LABEL_DEF,
                "{\"id\":500,\"name\":\"Lumbridge\",\"tooltip\":\"\",\"elementId\":80,\"spriteId\":1442}");
        source.defs.put(PRIF_LODESTONE_DEF,
                "{\"id\":3700,\"name\":\"\",\"tooltip\":\"Prifddinas lodestone\",\"elementId\":2613,\"spriteId\":-1}");
        return source;
    }

    @Test
    void defaultReadsTheMainAreaOnly_andJoinsTheDefinition() {
        populated();

        List<WorldMapElement> all = index.query(Map.of());

        WorldMapElement lode = all.getFirst();
        assertAll(
                () -> assertEquals(3, all.size()),
                () -> assertEquals(List.of(28), source.areaReads),
                () -> assertEquals(LUMBRIDGE_LODESTONE_DEF, lode.id()),
                () -> assertEquals(3233, lode.tileX()),
                () -> assertEquals(3222, lode.tileY()),
                () -> assertEquals(HOME_TELEPORT_CATEGORY, lode.category()),
                () -> assertEquals("Lumbridge lodestone", lode.tooltip()),
                () -> assertEquals(28, lode.areaId()),
                () -> assertEquals(5, all.get(1).flags()),
                () -> assertEquals(1442, all.get(1).spriteId()));
    }

    @Test
    void radiusNearPrifddinas_inTheMainArea_findsNoGhost_butItsOwnAreaDoes() {
        populated();
        Map<String, Object> nearPrif = Map.of("tile_x", 2208, "tile_y", 3361, "radius", 10);

        assertTrue(index.query(nearPrif).isEmpty());

        Map<String, Object> inPrif = new HashMap<>(nearPrif);
        inPrif.put(WorldMapElements.AREA_KEY, PRIF_AREA);
        assertEquals(PRIF_AREA, index.query(inPrif).getFirst().areaId());
    }

    @Test
    void allAreas_readsEveryArea() {
        populated();
        source.all.addAll(source.areas.get(28));
        source.all.addAll(source.areas.get(PRIF_AREA));

        List<WorldMapElement> all = index.query(Map.of(WorldMapElements.AREA_KEY, WorldMapElements.ALL_AREAS));

        assertEquals(4, all.size());
    }

    @Test
    void radiusIsChebyshev_andPlaneCategoryNameFilter() {
        populated();
        Map<String, Object> near = Map.of("tile_x", 3230, "tile_y", 3230, "radius", 10);

        assertAll(
                () -> assertEquals(2, index.query(near).size(), "both Lumbridge placements are within 10"),
                () -> assertEquals(List.of(LUMBRIDGE_LODESTONE_DEF), ids(index.query(
                        Map.of("category", HOME_TELEPORT_CATEGORY, "tile_x", 3230, "tile_y", 3230, "radius", 10)))),
                () -> assertEquals(List.of(LUMBRIDGE_LABEL_DEF, LUMBRIDGE_LODESTONE_DEF),
                        ids(index.query(Map.of("name", "LUMBRIDGE"))), "name matches name or tooltip"),
                () -> assertTrue(index.query(Map.of("plane", 1)).isEmpty()));
    }

    @Test
    void filtersTheCacheCannotAnswer_matchNothing() {
        populated();

        assertTrue(index.query(Map.of("skill_id", 26)).isEmpty());
        assertTrue(index.query(Map.of("with_resources", true)).isEmpty());
    }

    @Test
    void areasAndDefinitions_areReadOnce() {
        populated();

        index.query(Map.of());
        index.query(Map.of("name", "x"));

        assertEquals(List.of(28), source.areaReads);
        assertEquals(3, source.defReads.get());
    }

    @Test
    void undecodableDefinition_stillYieldsThePlacement() {
        source.areas.put(28, List.of(new WmePlacement(28, 9, 1, 2, 0, 0)));

        WorldMapElement e = index.query(Map.of()).getFirst();

        assertEquals(-1, e.category());
        assertEquals("", e.name());
    }

    private static List<Integer> ids(List<WorldMapElement> elements) {
        return elements.stream().map(WorldMapElement::id).sorted().toList();
    }

    static final class FakeSource implements WorldMapElementIndex.Source {
        final Map<Integer, List<WmePlacement>> areas = new HashMap<>();
        final List<WmePlacement> all = new ArrayList<>();
        final Map<Integer, String> defs = new HashMap<>();
        final List<Integer> areaReads = new ArrayList<>();
        final AtomicInteger defReads = new AtomicInteger();

        @Override
        public List<WmePlacement> area(int areaId) {
            areaReads.add(areaId);
            return areas.getOrDefault(areaId, List.of());
        }

        @Override
        public List<WmePlacement> all() {
            return all;
        }

        @Override
        public Optional<String> definitionJson(int elementId) {
            defReads.incrementAndGet();
            return Optional.ofNullable(defs.get(elementId));
        }
    }
}
