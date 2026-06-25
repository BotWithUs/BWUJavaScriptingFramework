package com.botwithus.bot.skilling.atlas;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the Atlas reader + closure port against a real {@code resolved.sqlite}.
 * Skips cleanly (JUnit Assumptions) when no Atlas is present, so CI without the
 * baked db is green. Point it at one with:
 * <pre>./gradlew :skilling-core:test -Dbotwithus.atlas=&lt;path&gt;\resolved.sqlite</pre>
 */
class AtlasClosureTest {

    // Item ids from the RS3 cache (stable): BRONZE_BAR and its ores, iron ore.
    private static final int BRONZE_BAR = 2349;
    private static final int COPPER_ORE = 436;
    private static final int TIN_ORE = 438;
    private static final int IRON_ORE = 440;

    private static Atlas atlas;

    @BeforeAll
    static void open() {
        Optional<Path> p = AtlasPaths.locate();
        assumeTrue(p.isPresent(),
                "No resolved.sqlite found (set -Dbotwithus.atlas=<path>); skipping Atlas tests");
        atlas = Atlas.open(p.get());
    }

    @AfterAll
    static void close() {
        if (atlas != null) {
            atlas.close();
        }
    }

    @Test
    void bronzeBarClosureNeeds100CopperAnd100Tin() {
        BuildPlan plan = atlas.closure(BRONZE_BAR, 100, Set.of());

        BuildPlan.RawMaterial copper = raw(plan, COPPER_ORE);
        BuildPlan.RawMaterial tin = raw(plan, TIN_ORE);
        assertEquals(100.0, copper.qty(), 0.001, "100 bronze bars need 100 copper ore");
        assertEquals(100.0, tin.qty(), 0.001, "100 bronze bars need 100 tin ore");
        assertFalse(copper.gatherSpots().isEmpty(), "copper ore should carry gather spots");
        assertFalse(plan.hasCycles(), "bronze bar closure should be acyclic");
    }

    @Test
    void recipeForBronzeBarIsSmithing() {
        Recipe r = atlas.recipe(BRONZE_BAR).orElseThrow();
        assertEquals("Smithing", r.skill());
        assertFalse(r.ingredients().isEmpty(), "bronze bar must have ingredients");
    }

    @Test
    void banksArePresent() {
        assertTrue(atlas.banks().size() > 100,
                "expected many bank placements, got " + atlas.banks().size());
    }

    @Test
    void ironOreHasGatherSpots() {
        assertFalse(atlas.gatherSpots(IRON_ORE).isEmpty(),
                "iron ore (440) should have gather spots");
    }

    @Test
    void normalTreesAreClusterKeyed() {
        // Normal Logs (1511) have no icon/skilling-table spots (level-1 trees), but
        // tree_clusters.py map-dumps + clusters them into item-keyed waypoints.
        List<Spot> normal = atlas.gatherSpots(1511);
        assertFalse(normal.isEmpty(), "normal Logs (1511) should be cluster-keyed now");
        assertTrue(normal.stream().anyMatch(s -> "tree_cluster".equals(s.kind())),
                "normal-tree spots should be tree_cluster waypoints");
        // Oak is item-keyed (icons + clusters); also reachable by loc name.
        assertFalse(atlas.gatherSpots(1521).isEmpty(), "oak (1521) should be item-keyed");
        assertFalse(atlas.gatherSpotsByLocName("Tree").isEmpty(),
                "normal trees still reachable by loc_name='Tree'");
    }

    @Test
    void resolvesBankDepositButtonFromGamevals() {
        NamedComponent c = atlas.component("BANK__BANK_INV_BUTTON").orElseThrow();
        assertEquals(517, c.interfaceId(), "bank interface");
        assertEquals(39, c.componentId(), "deposit-carried-inventory button");
    }

    @Test
    void resolvesWoodBoxItemIdFromGamevals() {
        assertEquals(54895, atlas.itemId("WOODCUTTING_WOODBOX_BASIC").orElseThrow(),
                "basic wood box item id by gameval");
        assertEquals(58253, atlas.itemId("WOODCUTTING_WOODBOX_ETERNAL").orElseThrow(),
                "eternal wood box item id by gameval");
    }

    @Test
    void resolvesWoodcuttingVarpByGameval() {
        assertEquals(10903, atlas.varpId("WOODCUTTING_WOODBOX_LASTUSED_TIER").orElseThrow(),
                "last-used wood box tier varp by gameval");
        assertEquals(10903, atlas.gamevalId("varp", "WOODCUTTING_WOODBOX_LASTUSED_TIER").orElseThrow(),
                "generic gamevalId resolves the same varp");
    }

    @Test
    void readsWoodBoxCapacityStructParam() {
        Struct s = atlas.struct("SKILLGUIDE_WOODCUTTING_WOOD_BOX_CAPACITY_1").orElseThrow();
        assertEquals(5, s.paramInt(2212).orElseThrow(),
                "first capacity step unlocks at Woodcutting level 5 (param 2212)");
        assertEquals(54895, s.paramInt(2213).orElseThrow(),
                "capacity struct points at the base wood box item (param 2213)");
    }

    private static BuildPlan.RawMaterial raw(BuildPlan plan, int item) {
        return plan.rawMaterials().stream()
                .filter(r -> r.item() == item)
                .findFirst()
                .orElseThrow(() -> new AssertionError("closure missing raw material " + item));
    }
}
