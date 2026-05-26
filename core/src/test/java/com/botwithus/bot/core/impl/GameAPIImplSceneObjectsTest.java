package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.SceneObjectInfo;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice-20 scene/ground query facade tests. Stubs the {@code query_locations}
 * and {@code query_ground_items} RPCs to return canned data so we can verify
 * the Java side wires up the rich wrappers, distance sort, definition cache,
 * and interaction params correctly. Real producer iteration lands later.
 */
class GameAPIImplSceneObjectsTest {

    private RpcClient rpc;
    private StubSnapshot snap;
    private Map<Integer, LocationType> locTypes;
    private Map<Integer, com.botwithus.bot.api.model.ItemType> itemTypes;
    private GameAPIImpl api;

    private GameAPIImpl build() {
        rpc = mock(RpcClient.class);
        snap = new StubSnapshot();
        locTypes = new HashMap<>();
        itemTypes = new HashMap<>();
        api = new GameAPIImpl(rpc, null, () -> snap) {
            @Override public LocationType getLocationType(int id) { return locTypes.get(id); }
            @Override public com.botwithus.bot.api.model.ItemType getItemType(int id) { return itemTypes.get(id); }
        };
        return api;
    }

    @Test
    void objectsFacadeIsSingleton() {
        build();
        assertSame(api.objects(), api.objects());
        assertSame(api.groundItems(), api.groundItems());
    }

    @Test
    void objectsQueryEmptyWhenRpcReturnsNothing() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of());
        assertNull(api.objects().query().nearest());
        assertEquals(0, api.objects().query().count());
    }

    @Test
    void objectsQueryReturnsRichWrappers() {
        build();
        snap.self = makeSelf(100, 100, 0);
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(1, 50, 105, 100, 0, "Tree", List.of("Chop down")),
                locReply(2, 50, 130, 100, 0, "Tree", List.of("Chop down")),
                locReply(3, 50, 102, 100, 0, "Tree", List.of("Chop down"))
        ));

        List<SceneObject> trees = api.objects().query().named("Tree").all();
        assertEquals(3, trees.size());
        assertEquals("Tree", trees.get(0).name());
        assertEquals(50, trees.get(0).typeId());
    }

    @Test
    void objectsNearestSortsByDistance() {
        build();
        snap.self = makeSelf(0, 0, 0);
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(1, 50, 30, 0, 0, "Rift", List.of("Convert")),
                locReply(2, 50, 5,  0, 0, "Rift", List.of("Convert")),
                locReply(3, 50, 10, 0, 0, "Rift", List.of("Convert"))
        ));

        SceneObject nearest = api.objects().query().named("Rift").nearest();
        assertNotNull(nearest);
        assertEquals(2, nearest.handle(), "expected the handle=2 (5 tiles away) one");
    }

    @Test
    void objectsHasOptionUsesPreResolvedRawOptions() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(7, 50, 0, 0, 0, "Rift", List.of("Convert", "Examine"))
        ));

        SceneObject obj = api.objects().query().first();
        assertTrue(obj.hasOption("Convert"));
        assertTrue(obj.hasOption("convert"));
        assertFalse(obj.hasOption("Attack"));
        // No LocationType lookup needed since raw.options was non-empty
        assertEquals(0, api.objects().definitionCacheSize());
    }

    @Test
    void objectsHasOptionFallsBackToLocationTypeWhenRawEmpty() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(7, 50, 0, 0, 0, "Rift", List.of()) // empty options on the wire
        ));
        locTypes.put(50, makeLoc(50, "Rift", List.of("Convert")));

        SceneObject obj = api.objects().query().first();
        assertTrue(obj.hasOption("Convert"));
    }

    @Test
    void objectsInteractByOptionResolvesIndex() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(7, 50, 0, 0, 0, "Rift", List.of("Convert", "Examine"))
        ));

        SceneObject obj = api.objects().query().first();
        assertTrue(obj.interact("Examine"));
        // Examine = option 2 → ActionTypes.OBJECT2 = 4, target = handle
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 4, "param1", 7, "param2", 0, "param3", 0)));
    }

    @Test
    void objectsInteractByMissingOptionReturnsFalse() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(7, 50, 0, 0, 0, "Rift", List.of("Convert"))
        ));

        SceneObject obj = api.objects().query().first();
        assertFalse(obj.interact("Talk-to"));
        verify(rpc, times(0)).callSync(eq("queue_action"), anyMap());
    }

    @Test
    void objectsLocationTypeCachedByTypeId() {
        build();
        when(rpc.callSyncList(eq("query_locations"), anyMap())).thenReturn(List.of(
                locReply(1, 50, 0, 0, 0, "", List.of()),
                locReply(2, 50, 0, 0, 0, "", List.of())
        ));
        locTypes.put(50, makeLoc(50, "Rift", List.of("Convert")));

        api.objects().query().all().forEach(SceneObject::name);
        assertEquals(1, api.objects().definitionCacheSize());
    }

    // ---------------------------------------------------------------- Ground items

    @Test
    void groundItemsQueryReturnsRichWrappers() {
        build();
        snap.self = makeSelf(0, 0, 0);
        when(rpc.callSyncList(eq("query_ground_items"), anyMap())).thenReturn(List.of(
                groundReply(1, 995, 1000, 5, 0, 0)
        ));
        itemTypes.put(995, makeItem(995, "Coins", List.of("Take")));

        GroundItem coins = api.groundItems().query().nearest();
        assertNotNull(coins);
        assertEquals("Coins", coins.name());
        assertEquals(1000, coins.quantity());
    }

    @Test
    void groundItemsInteractByOption() {
        build();
        when(rpc.callSyncList(eq("query_ground_items"), anyMap())).thenReturn(List.of(
                groundReply(7, 995, 1, 0, 0, 0)
        ));
        itemTypes.put(995, makeItem(995, "Coins", List.of("Take", "Examine")));

        GroundItem g = api.groundItems().query().first();
        assertTrue(g.interact("Take"));
        verify(rpc).callSync(eq("queue_action"),
                eq(Map.of("action_id", 18, "param1", 7, "param2", 0, "param3", 0)));
    }

    // ---------------------------------------------------------------- helpers

    private static LocalPlayer makeSelf(int x, int y, int plane) {
        return new LocalPlayer(0, 100, x, y, plane, 0, -1, -1, 0, -1, 0, true, List.of());
    }

    private static Map<String, Object> locReply(int handle, int typeId, int x, int y, int plane,
                                                String name, List<String> options) {
        Map<String, Object> m = new HashMap<>();
        m.put("handle", handle);
        m.put("type_id", typeId);
        m.put("tile_x", x);
        m.put("tile_y", y);
        m.put("plane", plane);
        m.put("name", name);
        m.put("options", options);
        return m;
    }

    private static Map<String, Object> groundReply(int handle, int itemId, int qty,
                                                    int x, int y, int plane) {
        Map<String, Object> m = new HashMap<>();
        m.put("handle", handle);
        m.put("item_id", itemId);
        m.put("quantity", qty);
        m.put("tile_x", x);
        m.put("tile_y", y);
        m.put("plane", plane);
        return m;
    }

    private static LocationType makeLoc(int id, String name, List<String> options) {
        return new LocationType(id, name, 1, 1, 0, 0, false, options, -1, -1, List.of(), 0, Map.of());
    }

    private static com.botwithus.bot.api.model.ItemType makeItem(int id, String name, List<String> ground) {
        return new com.botwithus.bot.api.model.ItemType(
                id, name, false, false, 0, 0, 0, -1, -1, false,
                ground, List.of(), Map.of());
    }

    private static final class StubSnapshot implements GameSnapshot {
        LocalPlayer self;

        @Override public long tickId() { return 0; }
        @Override public int gameState() { return 30; }
        @Override public int ownIndex() { return 0; }
        @Override public LocalPlayer self() { return self; }

        @Override public Npcs npcs() {
            return new Npcs() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Npc at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Npc> byServerIndex(int s) { return Optional.empty(); }
                @Override public List<com.botwithus.bot.api.snapshot.Npc> filter(com.botwithus.bot.api.snapshot.NpcFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Npc> stream() { return Stream.empty(); }
            };
        }
        @Override public Players players() {
            return new Players() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Player at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Player> byServerIndex(int s) { return Optional.empty(); }
                @Override public List<com.botwithus.bot.api.snapshot.Player> filter(com.botwithus.bot.api.snapshot.PlayerFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Player> stream() { return Stream.empty(); }
            };
        }
        @Override public Locations locations() {
            return new Locations() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Location at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public List<com.botwithus.bot.api.snapshot.Location> filter(com.botwithus.bot.api.snapshot.LocationFilter f) { return List.of(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Location> stream() { return Stream.empty(); }
            };
        }
        @Override public Inventories inventories() {
            return new Inventories() {
                @Override public int count() { return 0; }
                @Override public com.botwithus.bot.api.snapshot.Inventory at(int i) { throw new IndexOutOfBoundsException(); }
                @Override public Optional<com.botwithus.bot.api.snapshot.Inventory> byInvId(int id) { return Optional.empty(); }
                @Override public Stream<com.botwithus.bot.api.snapshot.Inventory> stream() { return Stream.empty(); }
            };
        }
        @Override public int sceneVersion() { return 0; }
    }
}
