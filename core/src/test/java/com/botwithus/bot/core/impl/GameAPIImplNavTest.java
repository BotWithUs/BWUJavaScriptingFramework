package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.*;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests GameAPIImpl navigation link, teleport, and region cache RPC mappings.
 */
class GameAPIImplNavTest {

    private RpcClient rpc;
    private GameAPIImpl api;

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        api = new GameAPIImpl(rpc);
    }

    // ============================== Region Cache ==============================

    @Test
    void getRegionCacheSize() {
        when(rpc.callSync("region_cache_info", Map.of())).thenReturn(Map.of("cache_size", 42));
        assertEquals(42, api.getRegionCacheSize());
    }

    @Test
    void clearRegionCache() {
        when(rpc.callSync("region_cache_clear", Map.of())).thenReturn(Map.of("ok", true));
        api.clearRegionCache();
        verify(rpc).callSync("region_cache_clear", Map.of());
    }

    // ============================== Transports ==============================

    @Test
    void navAddTransportMinimalParams() {
        when(rpc.callSync(eq("nav.add_transport"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddTransport(new NavTransport(100, 3200, 3200, 3210, 3210));

        verify(rpc).callSync(eq("nav.add_transport"), argThat(params -> {
            // Default plane=0, shape=10, rotation=0, optionIndex=0, destPlane=0 should be omitted
            assertEquals(100, params.get("object_id"));
            assertEquals(3200, params.get("x"));
            assertEquals(3200, params.get("y"));
            assertEquals(3210, params.get("dest_x"));
            assertEquals(3210, params.get("dest_y"));
            assertNull(params.get("plane"));      // 0 → omitted
            assertNull(params.get("rotation"));    // 0 → omitted
            assertNull(params.get("option_index"));// 0 → omitted
            assertNull(params.get("dest_plane"));  // 0 → omitted
            return true;
        }));
    }

    @Test
    void navAddTransportAllParams() {
        when(rpc.callSync(eq("nav.add_transport"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddTransport(new NavTransport(100, 3200, 3200, 1, 5, 2, 3, 3210, 3210, 2));

        verify(rpc).callSync(eq("nav.add_transport"), argThat(params -> {
            assertEquals(1, params.get("plane"));
            assertEquals(5, params.get("shape"));
            assertEquals(2, params.get("rotation"));
            assertEquals(3, params.get("option_index"));
            assertEquals(2, params.get("dest_plane"));
            return true;
        }));
    }

    @Test
    void navRemoveTransport() {
        when(rpc.callSync(eq("nav.remove_transport"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemoveTransport(100, 3200, 3200, 0);

        verify(rpc).callSync(eq("nav.remove_transport"), argThat(params -> {
            assertEquals(100, params.get("object_id"));
            assertEquals(3200, params.get("x"));
            assertEquals(3200, params.get("y"));
            assertNull(params.get("plane")); // 0 → omitted
            return true;
        }));
    }

    @Test
    void navRemoveTransportWithPlane() {
        when(rpc.callSync(eq("nav.remove_transport"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemoveTransport(100, 3200, 3200, 1);

        verify(rpc).callSync(eq("nav.remove_transport"), argThat(params -> {
            assertEquals(1, params.get("plane"));
            return true;
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void navListTransports() {
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("object_id", 100);
        t1.put("x", 3200);
        t1.put("y", 3200);
        t1.put("plane", 0);
        t1.put("shape", 10);
        t1.put("rotation", 0);
        t1.put("option_index", 0);
        t1.put("dest_x", 3210);
        t1.put("dest_y", 3210);
        t1.put("dest_plane", 0);

        when(rpc.callSync("nav.list_transports", Map.of()))
                .thenReturn(Map.of("count", 1, "transports", List.of(t1)));

        List<NavTransport> result = api.navListTransports();
        assertEquals(1, result.size());
        assertEquals(100, result.get(0).objectId());
        assertEquals(3210, result.get(0).destX());
    }

    // ============================== Doors ==============================

    @Test
    void navAddDoorMinimalParams() {
        when(rpc.callSync(eq("nav.add_door"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddDoor(new NavDoor(200, 3100, 3100));

        verify(rpc).callSync(eq("nav.add_door"), argThat(params -> {
            assertEquals(200, params.get("object_id"));
            assertEquals(3100, params.get("x"));
            assertEquals(3100, params.get("y"));
            assertNull(params.get("plane"));
            assertNull(params.get("shape"));
            assertNull(params.get("rotation"));
            return true;
        }));
    }

    @Test
    void navAddDoorAllParams() {
        when(rpc.callSync(eq("nav.add_door"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddDoor(new NavDoor(200, 3100, 3100, 1, 2, 3));

        verify(rpc).callSync(eq("nav.add_door"), argThat(params -> {
            assertEquals(1, params.get("plane"));
            assertEquals(2, params.get("shape"));
            assertEquals(3, params.get("rotation"));
            return true;
        }));
    }

    @Test
    void navRemoveDoor() {
        when(rpc.callSync(eq("nav.remove_door"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemoveDoor(200, 3100, 3100, 0);
        verify(rpc).callSync(eq("nav.remove_door"), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void navListDoors() {
        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("object_id", 200);
        d1.put("x", 3100);
        d1.put("y", 3100);
        d1.put("plane", 0);
        d1.put("shape", 0);
        d1.put("rotation", 0);

        when(rpc.callSync("nav.list_doors", Map.of()))
                .thenReturn(Map.of("count", 1, "doors", List.of(d1)));

        List<NavDoor> result = api.navListDoors();
        assertEquals(1, result.size());
        assertEquals(200, result.get(0).objectId());
        assertEquals(3100, result.get(0).x());
    }

    // ============================== Shortcuts ==============================

    @Test
    void navAddShortcut() {
        when(rpc.callSync(eq("nav.add_shortcut"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddShortcut(new NavShortcut(300, 3050, 3050, 60));

        verify(rpc).callSync(eq("nav.add_shortcut"), argThat(params -> {
            assertEquals(300, params.get("object_id"));
            assertEquals(60, params.get("agility_level"));
            return true;
        }));
    }

    @Test
    void navAddShortcutDefaultAgility() {
        when(rpc.callSync(eq("nav.add_shortcut"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddShortcut(new NavShortcut(300, 3050, 3050, 0, 0, 0, 1));

        verify(rpc).callSync(eq("nav.add_shortcut"), argThat(params -> {
            // agility_level=1 is the default, should be omitted
            assertNull(params.get("agility_level"));
            return true;
        }));
    }

    @Test
    void navRemoveShortcut() {
        when(rpc.callSync(eq("nav.remove_shortcut"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemoveShortcut(300, 3050, 3050, 0);
        verify(rpc).callSync(eq("nav.remove_shortcut"), anyMap());
    }

    // ============================== Plane Transitions ==============================

    @Test
    void navAddPlaneTransition() {
        when(rpc.callSync(eq("nav.add_plane_transition"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddPlaneTransition(new NavPlaneTransition(400, 3000, 3000, 2));

        verify(rpc).callSync(eq("nav.add_plane_transition"), argThat(params -> {
            assertEquals(400, params.get("object_id"));
            assertEquals(3000, params.get("x"));
            // dest_x=-1 should NOT be sent (negative means auto)
            assertNull(params.get("dest_x"));
            assertNull(params.get("dest_y"));
            return true;
        }));
    }

    @Test
    void navAddPlaneTransitionWithDest() {
        when(rpc.callSync(eq("nav.add_plane_transition"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddPlaneTransition(new NavPlaneTransition(400, 3000, 3000, 0, 10, 0, 1, 1, 3010, 3010, 1));

        verify(rpc).callSync(eq("nav.add_plane_transition"), argThat(params -> {
            assertEquals(3010, params.get("dest_x"));
            assertEquals(3010, params.get("dest_y"));
            return true;
        }));
    }

    @Test
    void navRemovePlaneTransition() {
        when(rpc.callSync(eq("nav.remove_plane_transition"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemovePlaneTransition(400, 3000, 3000, 0);
        verify(rpc).callSync(eq("nav.remove_plane_transition"), anyMap());
    }

    // ============================== Climbovers ==============================

    @Test
    void navAddClimbover() {
        when(rpc.callSync(eq("nav.add_climbover"), anyMap())).thenReturn(Map.of("ok", true));
        api.navAddClimbover(new NavClimbover(500, 2900, 2900));

        verify(rpc).callSync(eq("nav.add_climbover"), argThat(params -> {
            assertEquals(500, params.get("object_id"));
            assertEquals(2900, params.get("x"));
            return true;
        }));
    }

    @Test
    void navRemoveClimbover() {
        when(rpc.callSync(eq("nav.remove_climbover"), anyMap())).thenReturn(Map.of("ok", true));
        api.navRemoveClimbover(500, 2900, 2900, 0);
        verify(rpc).callSync(eq("nav.remove_climbover"), anyMap());
    }

    // ============================== Batch Operations ==============================

    @Test
    void navLoadJson() {
        when(rpc.callSync(eq("nav.load_json"), anyMap())).thenReturn(Map.of("ok", true, "added", 2));

        List<NavTransport> links = List.of(
                new NavTransport(1, 10, 20, 30, 40),
                new NavTransport(2, 50, 60, 70, 80)
        );
        assertEquals(2, api.navLoadJson(links));

        verify(rpc).callSync(eq("nav.load_json"), argThat(params -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sent = (List<Map<String, Object>>) params.get("links");
            assertEquals(2, sent.size());
            assertEquals(1, sent.get(0).get("object_id"));
            assertEquals(2, sent.get(1).get("object_id"));
            return true;
        }));
    }

    @Test
    void navSaveLinks() {
        when(rpc.callSync(eq("nav.save_links"), anyMap())).thenReturn(Map.of("ok", true));
        api.navSaveLinks("test.bin");

        verify(rpc).callSync(eq("nav.save_links"), argThat(params -> {
            assertEquals("test.bin", params.get("path"));
            return true;
        }));
    }

    @Test
    void navSaveLinksNullPath() {
        when(rpc.callSync(eq("nav.save_links"), anyMap())).thenReturn(Map.of("ok", true));
        api.navSaveLinks(null);

        verify(rpc).callSync(eq("nav.save_links"), argThat(params -> {
            assertNull(params.get("path"));
            return true;
        }));
    }

    @Test
    void navLoadLinks() {
        when(rpc.callSync(eq("nav.load_links"), anyMap())).thenReturn(Map.of("ok", true, "loaded", 12));
        assertEquals(12, api.navLoadLinks("test.bin"));
    }

    // ============================== Stats ==============================

    @Test
    void navGetStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("regions", 500);
        stats.put("doors", 42);
        stats.put("shortcuts", 15);
        stats.put("plane_transitions", 30);
        stats.put("climbovers", 8);
        stats.put("transports", 25);
        stats.put("teleports", 85);
        stats.put("teleports_builtin", 70);
        stats.put("teleports_script", 15);

        when(rpc.callSync("nav.stats", Map.of())).thenReturn(stats);

        NavStats result = api.navGetStats();
        assertEquals(500, result.regions());
        assertEquals(42, result.doors());
        assertEquals(15, result.shortcuts());
        assertEquals(30, result.planeTransitions());
        assertEquals(8, result.climbovers());
        assertEquals(25, result.transports());
        assertEquals(85, result.teleports());
        assertEquals(70, result.teleportsBuiltin());
        assertEquals(15, result.teleportsScript());
    }

    // ============================== Teleports ==============================

    @Test
    void navRegisterTeleports() {
        when(rpc.callSync(eq("nav.register_teleports"), anyMap()))
                .thenReturn(Map.of("ok", true, "added", 5, "total", 85, "builtin", 70));

        assertEquals(5, api.navRegisterTeleports("{}", "item_teleports"));

        verify(rpc).callSync(eq("nav.register_teleports"), argThat(params -> {
            assertEquals("{}", params.get("json"));
            // "item_teleports" is the default, so format should be omitted
            assertNull(params.get("format"));
            return true;
        }));
    }

    @Test
    void navRegisterTeleportsGibsonFormat() {
        when(rpc.callSync(eq("nav.register_teleports"), anyMap()))
                .thenReturn(Map.of("ok", true, "added", 3));

        api.navRegisterTeleports("[]", "gibson");

        verify(rpc).callSync(eq("nav.register_teleports"), argThat(params -> {
            assertEquals("gibson", params.get("format"));
            return true;
        }));
    }

    @Test
    void navClearScriptTeleports() {
        when(rpc.callSync("nav.clear_script_teleports", Map.of()))
                .thenReturn(Map.of("ok", true, "removed", 15, "remaining", 70));

        assertEquals(15, api.navClearScriptTeleports());
    }

    @SuppressWarnings("unchecked")
    @Test
    void navListTeleports() {
        Map<String, Object> tp = new LinkedHashMap<>();
        tp.put("index", 0);
        tp.put("name", "Lumbridge Lodestone");
        tp.put("global", true);
        tp.put("dest_x", 3233);
        tp.put("dest_y", 3222);
        tp.put("dest_plane", 0);
        tp.put("cost", 80.0);
        tp.put("cost_quick", 25.0);
        tp.put("chain_steps", 4);
        tp.put("requirements", 0);
        tp.put("builtin", true);

        when(rpc.callSync(eq("nav.list_teleports"), anyMap()))
                .thenReturn(Map.of("count", 1, "teleports", List.of(tp)));

        List<NavTeleport> result = api.navListTeleports(false);
        assertEquals(1, result.size());
        NavTeleport t = result.get(0);
        assertEquals("Lumbridge Lodestone", t.name());
        assertTrue(t.global());
        assertEquals(3233, t.destX());
        assertEquals(80.0, t.cost());
        assertEquals(25.0, t.costQuick());
        assertTrue(t.builtin());
    }

    @Test
    void navListTeleportsScriptOnly() {
        when(rpc.callSync(eq("nav.list_teleports"), anyMap()))
                .thenReturn(Map.of("count", 0, "teleports", List.of()));

        api.navListTeleports(true);

        verify(rpc).callSync(eq("nav.list_teleports"), argThat(params -> {
            assertEquals(true, params.get("script_only"));
            return true;
        }));
    }

    @Test
    void navListTeleportsNotScriptOnly() {
        when(rpc.callSync(eq("nav.list_teleports"), anyMap()))
                .thenReturn(Map.of("count", 0, "teleports", List.of()));

        api.navListTeleports(false);

        verify(rpc).callSync(eq("nav.list_teleports"), argThat(params -> {
            // script_only=false should not be sent
            assertNull(params.get("script_only"));
            return true;
        }));
    }
}
