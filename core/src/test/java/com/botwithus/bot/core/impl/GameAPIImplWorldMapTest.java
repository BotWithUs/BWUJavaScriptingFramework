package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.ResourceItem;
import com.botwithus.bot.api.model.ResourceSection;
import com.botwithus.bot.api.model.SkillRequirement;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldMapPlacement;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Slice-21 world-map-element query tests. Stubs query_world_map_elements
 * to canned data and verifies the fluent Query accumulates RPC params,
 * applies post-filters, and sorts by distance.
 *
 * <p>rule-exception: {@code {rule:no-fqn}} — the stub snapshot references
 * {@code api.snapshot.*} record types fully qualified because the wrappers
 * are the API under test. Same convention as the production wrapper classes
 * in {@code api/.../entities/}.
 */
class GameAPIImplWorldMapTest {

    private RpcClient rpc;
    private StubSnapshot snap;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        api = new GameAPIImpl(rpc, null, () -> snap);
        return api;
    }

    @Test
    void mapElementsFacadeIsSingleton() {
        build();
        assertSame(api.mapElements(), api.mapElements());
    }

    @Test
    void emptyResultWhenStubbed() {
        build();
        when(rpc.callSyncList(eq("query_world_map_elements"), anyMap())).thenReturn(List.of());
        assertNull(api.mapElements().query().nearest());
        assertEquals(0, api.mapElements().query().count());
        assertFalse(api.mapElements().query().exists());
    }

    @Test
    void filterParamsRideOnRpcMap() {
        build();
        when(rpc.callSyncList(eq("query_world_map_elements"), anyMap())).thenReturn(List.of());
        snap.self = makeSelf(2200, 3300, 0);

        api.mapElements().query()
                .withCategory(3032)
                .withSkill(26, 1, 50)
                .withResources()
                .nearPlayer()
                .all();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(rpc).callSyncList(eq("query_world_map_elements"), captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertEquals(3032, sent.get("category"));
        assertEquals(26,   sent.get("skill_id"));
        assertEquals(1,    sent.get("min_level"));
        assertEquals(50,   sent.get("max_level"));
        assertEquals(true, sent.get("with_resources"));
        assertEquals(2200, sent.get("tile_x"));
        assertEquals(3300, sent.get("tile_y"));
    }

    @Test
    void parsesNestedRecords() {
        build();
        Map<String, Object> elem = new HashMap<>();
        elem.put("id", 7);
        elem.put("tile_x", 100); elem.put("tile_y", 200); elem.put("plane", 0);
        elem.put("category", 3032);
        elem.put("sprite_id", 0); elem.put("element_id", 7);
        elem.put("name", "Wisp");
        elem.put("tooltip", "Energy spot");
        elem.put("description", "Pale wisp");
        elem.put("min_level", 1);
        elem.put("level_tier1", 1);
        elem.put("level_tier2", -1);
        elem.put("level_tier3", -1);
        elem.put("skill_requirements", List.of(
                Map.of("skill_id", 26, "level", 1, "skill_name", "Divination")));
        elem.put("resources", List.of(
                Map.of("title", "Wisp", "items", List.of(
                        Map.of("item_id", 29316, "level", 1, "quantity", 1)))));
        elem.put("placements", List.of(
                Map.of("plane", 0, "tile_x", 100, "tile_y", 200, "members_only", false)));

        when(rpc.callSyncList(eq("query_world_map_elements"), anyMap()))
                .thenReturn(List.of(elem));

        WorldMapElement e = api.mapElements().query().first();
        assertNotNull(e);
        assertEquals(7, e.id());
        assertEquals("Wisp", e.name());
        assertEquals("Pale wisp", e.description());

        assertEquals(1, e.skillRequirements().size());
        SkillRequirement sr = e.skillRequirements().getFirst();
        assertEquals(26, sr.skillId());
        assertEquals(1,  sr.level());

        assertEquals(1, e.resources().size());
        ResourceSection rs = e.resources().getFirst();
        assertEquals("Wisp", rs.title());
        ResourceItem ri = rs.items().getFirst();
        assertEquals(29316, ri.itemId());

        assertEquals(1, e.placements().size());
        WorldMapPlacement pl = e.placements().getFirst();
        assertEquals(100, pl.tileX());
        assertFalse(pl.membersOnly());
    }

    @Test
    void sortByDistanceUsesPlayerWhenNoExplicitCenter() {
        build();
        snap.self = makeSelf(0, 0, 0);
        when(rpc.callSyncList(eq("query_world_map_elements"), anyMap())).thenReturn(List.of(
                bareElement(1, 30, 0),
                bareElement(2, 5, 0),
                bareElement(3, 10, 0)
        ));
        WorldMapElement nearest = api.mapElements().query().sortByDistance().nearest();
        assertNotNull(nearest);
        assertEquals(2, nearest.id());
    }

    @Test
    void postFilterAppliedAfterRpc() {
        build();
        when(rpc.callSyncList(eq("query_world_map_elements"), anyMap())).thenReturn(List.of(
                bareElement(1, 0, 0),
                bareElement(2, 0, 0)
        ));
        WorldMapElement e = api.mapElements().query()
                .filter(el -> el.id() == 2)
                .first();
        assertNotNull(e);
        assertEquals(2, e.id());
    }

    private static Map<String, Object> bareElement(int id, int x, int y) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("tile_x", x); m.put("tile_y", y); m.put("plane", 0);
        m.put("category", 0);
        m.put("sprite_id", 0); m.put("element_id", id);
        m.put("name", ""); m.put("tooltip", ""); m.put("description", "");
        m.put("min_level", -1);
        m.put("level_tier1", -1); m.put("level_tier2", -1); m.put("level_tier3", -1);
        m.put("skill_requirements", List.of());
        m.put("resources", List.of());
        m.put("placements", List.of());
        return m;
    }

    private static LocalPlayer makeSelf(int x, int y, int plane) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, -1, List.of());
    }

    private static final class StubSnapshot implements GameSnapshot {
        LocalPlayer self;
        @Override public int serverTick() { return 0; }
        @Override public int gameCycle() { return 0; }
        @Override public long publishSeq() { return 0; }
        @Override public int gameState() { return 30; }
        @Override public int ownIndex() { return 0; }
        @Override public LocalPlayer self() { return self; }
        @Override public Npcs npcs() { return new Npcs() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.Npc at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public Optional<com.botwithus.bot.api.snapshot.Npc> byServerIndex(int s) { return Optional.empty(); }
            @Override public List<com.botwithus.bot.api.snapshot.Npc> filter(com.botwithus.bot.api.snapshot.NpcFilter f) { return List.of(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.Npc> stream() { return Stream.empty(); }
        }; }
        @Override public Players players() { return new Players() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.Player at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public Optional<com.botwithus.bot.api.snapshot.Player> byServerIndex(int s) { return Optional.empty(); }
            @Override public List<com.botwithus.bot.api.snapshot.Player> filter(com.botwithus.bot.api.snapshot.PlayerFilter f) { return List.of(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.Player> stream() { return Stream.empty(); }
        }; }
        @Override public Locations locations() { return new Locations() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.Location at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public List<com.botwithus.bot.api.snapshot.Location> filter(com.botwithus.bot.api.snapshot.LocationFilter f) { return List.of(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.Location> stream() { return Stream.empty(); }
        }; }
        @Override public Inventories inventories() { return new Inventories() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.Inventory at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public Optional<com.botwithus.bot.api.snapshot.Inventory> byInvId(int id) { return Optional.empty(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.Inventory> stream() { return Stream.empty(); }
        }; }
        @Override public GroundItems groundItems() { return new GroundItems() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.GroundItem at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public List<com.botwithus.bot.api.snapshot.GroundItem> filter(com.botwithus.bot.api.snapshot.GroundItemFilter f) { return List.of(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.GroundItem> stream() { return Stream.empty(); }
        }; }
        @Override public Projectiles projectiles() { return new Projectiles() {
            @Override public int count() { return 0; }
            @Override public com.botwithus.bot.api.snapshot.Projectile at(int i) { throw new IndexOutOfBoundsException(); }
            @Override public List<com.botwithus.bot.api.snapshot.Projectile> filter(com.botwithus.bot.api.snapshot.ProjectileFilter f) { return List.of(); }
            @Override public Stream<com.botwithus.bot.api.snapshot.Projectile> stream() { return Stream.empty(); }
        }; }
        @Override public int sceneVersion() { return 0; }
    }
}
